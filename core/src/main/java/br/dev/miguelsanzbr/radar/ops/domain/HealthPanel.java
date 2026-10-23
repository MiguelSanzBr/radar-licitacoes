package br.dev.miguelsanzbr.radar.ops.domain;

import java.util.List;

/**
 * What the operator looks at. A section that could not be read is empty (the outbox, null) and its
 * component says DOWN with the reason.
 */
public record HealthPanel(
    Status status,
    List<Component> components,
    OutboxBacklog outbox,
    List<IngestionRun> lastRuns,
    List<Cursor> cursors,
    List<ConsumerLag> consumers,
    List<DlqCount> dlq,
    List<Checkpoint> projection,
    List<FlagState> flags) {

  public HealthPanel {
    components = List.copyOf(components);
    lastRuns = List.copyOf(lastRuns);
    cursors = List.copyOf(cursors);
    consumers = List.copyOf(consumers);
    dlq = List.copyOf(dlq);
    projection = List.copyOf(projection);
    flags = List.copyOf(flags);
  }
}
