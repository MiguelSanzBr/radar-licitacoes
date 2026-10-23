package br.dev.miguelsanzbr.radar.ops;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.SharedApicurio;
import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.RegistryHttp;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.TopicReader;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dead-letter path and a registry outage in avro mode, on a real broker, database and Apicurio.
 * A record the projector rejects goes to the dead letters as text (no Avro content type), is
 * recorded as the JSON of the event and, after the operator's reprocess, is projected. A registry
 * that stops answering while a record is read only delays it: the record is retried, never
 * dead-lettered, and is projected when the registry is back. Own topics and groups.
 */
@Tag("avro")
@KafkaSpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(
    properties = {
      "radar.messaging.format=avro",
      "radar.messaging.registry-timeout-ms=2000",
      "radar.outbox.topics.Procurement=" + AvroDlqIT.TOPIC,
      "radar.outbox.topics.Alert=radar.alert.avrodlq-it.v1",
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-avrodlq-it",
      "radar.ops.dlq-recorder.enabled=true",
      "radar.ops.dlq-recorder.group=ops-dlq-recorder-avrodlq-it",
      // why: a fresh 12 partition topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class AvroDlqIT {

  static final String TOPIC = "radar.procurement.avrodlq-it.v1";
  private static final String DLQ = TOPIC + ".dlq";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static {
    try {
      var builder =
          new ProcessBuilder(
              "bash",
              Path.of("..", "scripts", "schemas-register.sh").toString(),
              SharedApicurio.url());
      builder.environment().put("RADAR_TOPICS", TOPIC);
      var process = builder.redirectErrorStream(true).start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as(output).isZero();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void registry(DynamicPropertyRegistry properties) {
    properties.add("radar.messaging.registry-url", SharedApicurio::url);
  }

  @Autowired KafkaTemplate<String, String> template;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired MeterRegistry meters;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @MockitoSpyBean ProcurementProjector projector;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE ops.dlq_message, search.procurement_search_view,"
            + " search.procurement_detail_view, search.processed_event,"
            + " search.projection_checkpoint");
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private static void pause(boolean paused) {
    var docker = SharedApicurio.INSTANCE.getDockerClient();
    var id = SharedApicurio.INSTANCE.getContainerId();
    if (paused) {
      docker.pauseContainerCmd(id).exec();
    } else {
      docker.unpauseContainerCmd(id).exec();
    }
  }

  @Test
  @Order(1)
  void aRegistryThatStopsAnsweringOnlyDelaysTheRecordAndNothingIsDeadLettered() throws Exception {
    var container = listeners.getListenerContainer("search-projector-listener");
    container.stop();
    var event = discovered("outage", view(id(7), "Contratação de serviços", "1000.00", 1), 1);
    template.send(TOPIC, event.aggregateId(), envelope(event)).get(120, TimeUnit.SECONDS);

    pause(true);
    try {
      container.start();
      // the registry is paused: every attempt to decode ends at the 2 s bound, and the record waits
      await()
          .during(Duration.ofSeconds(12))
          .atMost(Duration.ofSeconds(30))
          .until(() -> count("SELECT count(*) FROM search.procurement_search_view") == 0);
    } finally {
      pause(false);
    }

    await()
        .atMost(Duration.ofSeconds(120))
        .until(() -> count("SELECT count(*) FROM search.procurement_search_view") == 1);
    assertThat(count("SELECT count(*) FROM ops.dlq_message")).isZero();
    assertThat(meters.counter("dlq_messages_total", "topic", DLQ).count()).isZero();
  }

  @Test
  @Order(2)
  void aRejectedAvroEventIsDeadLetteredAsTextRecordedAsJsonAndProjectedAfterTheReprocess()
      throws Exception {
    doThrow(new IllegalArgumentException("rejected until fixed"))
        .when(projector)
        .project(any(), anyLong());
    var event = discovered("flow", view(id(1), "Contratação de serviços", "1000.00", 1), 1);
    var envelope = envelope(event);
    template.send(TOPIC, event.aggregateId(), envelope).get(120, TimeUnit.SECONDS);

    await()
        .atMost(Duration.ofSeconds(120))
        .until(() -> count("SELECT count(*) FROM ops.dlq_message") == 1);

    var row = jdbc.queryForMap("SELECT * FROM ops.dlq_message");
    assertThat(row)
        .containsEntry("topic", DLQ)
        .containsEntry("original_topic", TOPIC)
        .containsEntry("error_class", "java.lang.IllegalArgumentException")
        .containsEntry("reprocessed_at", null);
    // the DLQ keeps the JSON of the event, not the Avro bytes
    assertThat(JSON.readTree((String) row.get("message_value"))).isEqualTo(JSON.readTree(envelope));
    var dead = TopicReader.readUntil(DLQ, records -> !records.isEmpty(), Duration.ofSeconds(30));
    assertThat(dead).hasSize(1);
    assertThat(dead.getFirst().headers().lastHeader("content-type")).isNull();
    assertThat(JSON.readTree(dead.getFirst().value())).isEqualTo(JSON.readTree(envelope));

    Mockito.reset(projector);
    mvc.perform(post("/ops/dlq/" + DLQ + "/reprocess").with(Auth.operator()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.count").value(1));

    await()
        .atMost(Duration.ofSeconds(120))
        .until(() -> count("SELECT count(*) FROM search.procurement_search_view") == 1);
    assertThat(jdbc.queryForMap("SELECT reprocess_result FROM ops.dlq_message"))
        .containsEntry("reprocess_result", "REPUBLISHED");
    // the registry rule of the group is the one the script set
    var rule =
        new RegistryHttp(SharedApicurio.url())
            .call("GET", "/groups/" + TOPIC + "/rules/COMPATIBILITY", null);
    assertThat(rule.body()).contains("BACKWARD");
  }
}
