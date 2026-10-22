package br.dev.miguelsanzbr.radar.observability;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.outbox.OutboxRelay;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaTestConfig;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.TopicReader;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * One HTTP request writes a procurement and its outbox rows; the relay then publishes them. The
 * trace of that request has to show up in the outbox headers and in the Kafka records.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "radar.outbox.relay-enabled=false",
      "radar.outbox.publish-timeout=10s",
      // why: no collector in tests; propagation needs the tracing export switch on
      "management.tracing.export.otlp.enabled=false"
    })
@AutoConfigureTracing
@Import({PostgresTestConfig.class, KafkaTestConfig.class, TracePropagationIT.Probe.class})
class TracePropagationIT {

  private static final int SEQUENCE = 900_101;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** In-memory span capture and the write path that the real PNCP collection will provide. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Probe {

    @Bean
    InMemorySpanExporter spanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    SpanProcessor spanCapture(InMemorySpanExporter exporter) {
      return SimpleSpanProcessor.create(exporter);
    }

    @Bean
    WriteController writeController(
        ProcurementRepository repository, OutboxAppender appender, TransactionTemplate tx) {
      return new WriteController(repository, appender, tx);
    }
  }

  @RestController
  static class WriteController {
    private final ProcurementRepository repository;
    private final OutboxAppender appender;
    private final TransactionTemplate transaction;

    WriteController(
        ProcurementRepository repository, OutboxAppender appender, TransactionTemplate tx) {
      this.repository = repository;
      this.appender = appender;
      this.transaction = tx;
    }

    @PostMapping("/ops/test/procurement")
    Map<String, String> write() {
      transaction.executeWithoutResult(
          status -> {
            var discovery = Procurement.discover(snapshot(id(SEQUENCE)), clockAt(T0));
            repository.save(discovery.procurement());
            appender.append("Procurement", discovery.procurement().revision(), discovery.events());
          });
      return Map.of(
          "traceId", MDC.get("traceId"),
          "spanId", MDC.get("spanId"),
          "correlationId", MDC.get("correlationId"));
    }
  }

  @LocalServerPort int port;
  // why: /ops needs the operator role (ADR-0019); this stub decodes the test's bearer token
  @MockitoBean JwtDecoder decoder;
  @Autowired InMemorySpanExporter exporter;
  @Autowired OutboxRelay relay;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void operatorToken() {
    when(decoder.decode("operator"))
        .thenReturn(
            Jwt.withTokenValue("operator")
                .header("alg", "RS256")
                .subject("ops")
                .claim("realm_access", Map.of("roles", List.of("operator")))
                .build());
  }

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
    jdbc.update(
        "DELETE FROM ingest.procurement_situation_history WHERE numero_controle_pncp = ?",
        id(SEQUENCE).value());
    jdbc.update(
        "DELETE FROM ingest.procurement WHERE numero_controle_pncp = ?", id(SEQUENCE).value());
    exporter.reset();
  }

  private HttpResponse<String> post(String correlationId) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ops/test/procurement"))
                .header("Authorization", "Bearer operator")
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String traceIdOf(String traceparent) {
    var parts = traceparent.split("-");
    assertThat(parts).hasSize(4);
    assertThat(parts[0]).isEqualTo("00");
    return parts[1];
  }

  @Test
  void theTraceOfTheRequestReachesTheOutboxRowAndTheKafkaRecordAndTheLogContext() throws Exception {
    var response = post("it-correlation-1");
    assertThat(response.statusCode()).isEqualTo(200);

    var serverSpan =
        await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () ->
                    exporter.getFinishedSpanItems().stream()
                        .filter(span -> span.getKind() == SpanKind.SERVER)
                        .findFirst(),
                java.util.Optional::isPresent)
            .orElseThrow();
    var traceId = serverSpan.getTraceId();

    // log context: the MDC seen by the handler carries the same ids as the span
    var seen = JSON.readTree(response.body());
    assertThat(seen.get("traceId").asString()).isEqualTo(traceId);
    assertThat(seen.get("spanId").asString()).isNotBlank();
    assertThat(seen.get("correlationId").asString()).isEqualTo("it-correlation-1");
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains("it-correlation-1");

    // outbox: every row of the request stores the traceparent of that trace
    var rows =
        jdbc.queryForList(
            "SELECT event_id::text AS id, headers->>'traceparent' AS traceparent FROM outbox.outbox_event");
    assertThat(rows).isNotEmpty();
    var stored = new java.util.HashMap<String, String>();
    for (var row : rows) {
      var traceparent = (String) row.get("traceparent");
      assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[13]");
      assertThat(traceIdOf(traceparent)).isEqualTo(traceId);
      stored.put((String) row.get("id"), traceparent);
    }

    // kafka: the relay is async, yet the records continue the original trace
    assertThat(relay.relayOnce()).isEqualTo(stored.size());
    var records =
        TopicReader.readUntil(
            "radar.procurement.v1",
            all -> all.stream().filter(TracePropagationIT.this::isOurs).count() >= stored.size(),
            Duration.ofSeconds(30));
    var ours = records.stream().filter(this::isOurs).toList();
    assertThat(ours).hasSize(stored.size());
    for (var record : ours) {
      var headers = record.headers().headers("traceparent");
      assertThat(headers).hasSize(1);
      assertThat(traceIdOf(new String(headers.iterator().next().value()))).isEqualTo(traceId);
      var envelope = JSON.readTree(record.value());
      assertThat(envelope.get("traceparent").asString())
          .isEqualTo(stored.get(envelope.get("eventId").asString()));
    }
    assertThat(
            exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.PRODUCER)
                .map(SpanData::getTraceId))
        .contains(traceId);
  }

  private boolean isOurs(ConsumerRecord<String, String> record) {
    return record.key().equals(id(SEQUENCE).value());
  }
}
