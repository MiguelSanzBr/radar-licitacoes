package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.observability.TraceparentInjector;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class OutboxWriterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");
  private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  /** Captures the INSERT parameters instead of talking to a database. */
  private static final class RecordingJdbc extends JdbcTemplate {
    final List<Object[]> rows = new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
      rows.add(args);
      return 1;
    }
  }

  private static TraceparentInjector tracing(String traceparent) {
    return new TraceparentInjector(Tracer.NOOP, Propagator.NOOP) {
      @Override
      public String current() {
        return traceparent;
      }
    };
  }

  private static OutboxWriter writer(RecordingJdbc jdbc, String traceparent) {
    return new OutboxWriter(
        jdbc, new UuidV7(InstantSource.fixed(AT), new SecureRandom()), tracing(traceparent));
  }

  record Payload(
      Money value, ProcurementId procurement, IbgeCode city, UUID owner, Instant happenedAt) {}

  @Test
  void storesTheEnvelopeColumnsAndSerializesSharedValueTypesAsText() throws Exception {
    var jdbc = new RecordingJdbc();
    var owner = UUID.fromString("0198a3c2-4f10-7000-8000-000000000001");
    var payload =
        new Payload(
            Money.of("1500.5"),
            new ProcurementId("83021808000182-1-000001/2026"),
            new IbgeCode("4205407"),
            owner,
            AT);

    writer(jdbc, TRACE).write("Alert", "subscriber-1", 4, "AlertMatched", AT, payload);

    assertThat(jdbc.rows).hasSize(1);
    var row = jdbc.rows.getFirst();
    assertThat((UUID) row[0]).extracting(UUID::version).isEqualTo(7);
    assertThat(row[1]).isEqualTo("Alert");
    assertThat(row[2]).isEqualTo("subscriber-1");
    assertThat(row[3]).isEqualTo("AlertMatched");
    assertThat(row[4]).isEqualTo(1);
    var body = JSON.readTree((String) row[5]);
    assertThat(body.get("value").asString()).isEqualTo("1500.50");
    assertThat(body.get("procurement").asString()).isEqualTo("83021808000182-1-000001/2026");
    assertThat(body.get("city").asString()).isEqualTo("4205407");
    assertThat(body.get("owner").asString()).isEqualTo(owner.toString());
    assertThat(body.get("happenedAt").asString()).isEqualTo("2026-10-22T12:00:00Z");
    var headers = JSON.readTree((String) row[6]);
    assertThat(headers.get("eventType").asString()).isEqualTo("AlertMatched");
    assertThat(headers.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(headers.get("aggregateVersion").asLong()).isEqualTo(4);
    assertThat(headers.get("occurredAt").asString()).isEqualTo("2026-10-22T12:00:00Z");
    assertThat(headers.get("traceparent").asString()).isEqualTo(TRACE);
  }

  @Test
  void omitsTheTraceparentHeaderWhenNoSpanIsActive() throws Exception {
    var jdbc = new RecordingJdbc();

    writer(jdbc, null).write("Alert", "subscriber-1", 1, "AlertMatched", AT, java.util.Map.of());

    assertThat(JSON.readTree((String) jdbc.rows.getFirst()[6]).has("traceparent")).isFalse();
  }
}
