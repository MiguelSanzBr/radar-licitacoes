package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.time.Instant;
import java.util.UUID;

/** Inbox of the consumers: remembers which events were already handled. */
public interface ProcessedEvents {

  /** True the first time an event is seen by a consumer, false for every repeat. */
  boolean firstTime(String consumer, UUID eventId, Instant at);
}
