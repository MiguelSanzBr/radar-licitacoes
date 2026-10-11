package br.dev.miguelsanzbr.radar.shared;

import java.time.Instant;

/**
 * Marker for events raised by aggregates. Not sealed: a sealed interface cannot permit types in
 * other packages outside a named module, so each context declares its own sealed family that
 * implements this one.
 */
public interface DomainEvent {

  String aggregateId();

  Instant occurredAt();

  default String eventType() {
    return getClass().getSimpleName();
  }
}
