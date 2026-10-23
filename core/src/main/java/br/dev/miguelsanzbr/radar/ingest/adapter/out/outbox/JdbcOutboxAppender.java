package br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox;

import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.outbox.OutboxWriter;
import br.dev.miguelsanzbr.radar.shared.DomainEvent;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends the events of a domain aggregate to the shared outbox, one row per event. */
@Component
public class JdbcOutboxAppender implements OutboxAppender {

  private final OutboxWriter writer;

  JdbcOutboxAppender(OutboxWriter writer) {
    this.writer = writer;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      String aggregateType, long aggregateVersion, List<? extends DomainEvent> events) {
    for (var event : events) {
      writer.write(
          aggregateType,
          event.aggregateId(),
          aggregateVersion,
          event.eventType(),
          event.occurredAt(),
          event);
    }
  }
}
