package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.shared.DomainEvent;
import java.util.List;

/** Only the append call of the outbox; SpotBugs reads a stored "append" type as a mutable list. */
@FunctionalInterface
interface EventSink {
  void append(String aggregateType, long aggregateVersion, List<? extends DomainEvent> events);
}
