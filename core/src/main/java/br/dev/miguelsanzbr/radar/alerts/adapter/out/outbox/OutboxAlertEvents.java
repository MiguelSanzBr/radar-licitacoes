package br.dev.miguelsanzbr.radar.alerts.adapter.out.outbox;

import br.dev.miguelsanzbr.radar.alerts.application.port.AlertEvents;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.outbox.OutboxWriter;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Writes alert events to the shared outbox; the topic key is the subscriber id. */
@Component
public class OutboxAlertEvents implements AlertEvents {

  private static final String AGGREGATE_TYPE = "Alert";

  /** Each alert is an independent fact, so every aggregate version is the first. */
  private static final long VERSION = 1;

  private final OutboxWriter writer;

  OutboxAlertEvents(OutboxWriter writer) {
    this.writer = writer;
  }

  @Override
  public void matched(AlertMatched event, Instant occurredAt) {
    writer.write(
        AGGREGATE_TYPE,
        event.subscriberId().toString(),
        VERSION,
        AlertMatched.class.getSimpleName(),
        occurredAt,
        event);
  }
}
