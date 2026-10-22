package br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox;

import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.observability.TraceparentInjector;
import br.dev.miguelsanzbr.radar.shared.DomainEvent;
import br.dev.miguelsanzbr.radar.shared.EventEnvelope;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Component
public class JdbcOutboxAppender implements OutboxAppender {

  private static final int SCHEMA_VERSION = 1;

  private static final String INSERT =
      """
      INSERT INTO outbox.outbox_event
        (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers)
      VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
      """;

  /** Money goes out as a plain decimal string ("441560.04"); ids and codes as their text. */
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .addModule(
              new SimpleModule()
                  .addSerializer(Money.class, new MoneySerializer())
                  .addSerializer(ProcurementId.class, ToStringSerializer.instance)
                  .addSerializer(IbgeCode.class, ToStringSerializer.instance))
          .build();

  private final JdbcTemplate jdbc;
  private final UuidV7 ids;
  private final TraceparentInjector traceparents;

  JdbcOutboxAppender(JdbcTemplate jdbc, UuidV7 ids, TraceparentInjector traceparents) {
    this.jdbc = jdbc;
    this.ids = ids;
    this.traceparents = traceparents;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      String aggregateType, long aggregateVersion, List<? extends DomainEvent> events) {
    // why: the relay runs later on another thread; the row keeps the trace of the writing request
    var traceparent = traceparents.current();
    for (var event : events) {
      var envelope =
          EventEnvelope.wrap(
              event, aggregateType, aggregateVersion, SCHEMA_VERSION, traceparent, ids);
      var headers = new LinkedHashMap<String, Object>();
      headers.put("eventType", envelope.eventType());
      headers.put("schemaVersion", envelope.schemaVersion());
      headers.put("aggregateVersion", envelope.aggregateVersion());
      headers.put("occurredAt", envelope.occurredAt().toString());
      if (traceparent != null) {
        headers.put("traceparent", traceparent);
      }
      jdbc.update(
          INSERT,
          envelope.eventId(),
          envelope.aggregateType(),
          envelope.aggregateId(),
          envelope.eventType(),
          envelope.schemaVersion(),
          JSON.writeValueAsString(envelope.payload()),
          JSON.writeValueAsString(headers));
    }
  }

  private static final class MoneySerializer extends ValueSerializer<Money> {
    @Override
    public void serialize(Money value, JsonGenerator gen, SerializationContext context) {
      gen.writeString(value.amount().toPlainString());
    }
  }
}
