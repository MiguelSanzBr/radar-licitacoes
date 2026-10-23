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

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.TopicReader;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole dead-letter path on a real broker and database: an event the projector rejects goes to
 * the dead-letter topic and into {@code ops.dlq_message}, and after the cause is fixed the
 * operator's reprocess puts it back and it is projected. Its own topics and groups keep it apart
 * from what other test classes leave on the shared broker.
 */
@KafkaSpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "radar.outbox.topics.Procurement=radar.procurement.dlqflow-it.v1",
      "radar.outbox.topics.Alert=radar.alert.dlqflow-it.v1",
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-dlqflow-it",
      "radar.ops.dlq-recorder.enabled=true",
      "radar.ops.dlq-recorder.group=ops-dlq-recorder-dlqflow-it",
      // why: a fresh 12 partition topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class DlqFlowIT {

  private static final String TOPIC = "radar.procurement.dlqflow-it.v1";
  private static final String DLQ = TOPIC + ".dlq";

  @Autowired KafkaTemplate<String, String> template;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired MeterRegistry registry;
  @MockitoSpyBean ProcurementProjector projector;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE ops.dlq_message, search.procurement_search_view,"
            + " search.procurement_detail_view, search.processed_event,"
            + " search.projection_checkpoint, search.procurement_stats_view");
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  @Test
  void aRejectedEventIsDeadLetteredRecordedAndProjectedAfterTheReprocess() throws Exception {
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
        .containsEntry("message_key", event.aggregateId())
        .containsEntry("message_value", envelope)
        .containsEntry("consumer_group", "search-projector-dlqflow-it")
        .containsEntry("original_topic", TOPIC)
        .containsEntry("error_class", "java.lang.IllegalArgumentException")
        .containsEntry("error_message", "rejected until fixed")
        .containsEntry("reprocessed_at", null);
    var dead = TopicReader.readUntil(DLQ, records -> !records.isEmpty(), Duration.ofSeconds(30));
    assertThat(dead).hasSize(1);
    assertThat(
            new String(
                dead.getFirst().headers().lastHeader("x-original-topic").value(),
                StandardCharsets.UTF_8))
        .isEqualTo(TOPIC);
    assertThat(count("SELECT count(*) FROM search.procurement_search_view")).isZero();
    assertThat(registry.counter("dlq_messages_total", "topic", DLQ).count()).isEqualTo(1.0);

    Mockito.reset(projector);
    mvc.perform(post("/ops/dlq/" + DLQ + "/reprocess").with(Auth.operator()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.count").value(1));

    await()
        .atMost(Duration.ofSeconds(120))
        .until(() -> count("SELECT count(*) FROM search.procurement_search_view") == 1);
    var done = jdbc.queryForMap("SELECT reprocessed_at, reprocess_result FROM ops.dlq_message");
    assertThat(done.get("reprocessed_at")).isNotNull();
    assertThat(done).containsEntry("reprocess_result", "REPUBLISHED");
    assertThat(registry.counter("dlq_messages_total", "topic", DLQ).count()).isEqualTo(1.0);

    mvc.perform(post("/ops/dlq/" + DLQ + "/reprocess").with(Auth.operator()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.count").value(0));
  }
}
