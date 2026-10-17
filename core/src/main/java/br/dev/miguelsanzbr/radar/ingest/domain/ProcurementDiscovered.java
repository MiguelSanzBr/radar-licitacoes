package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Instant;

public record ProcurementDiscovered(
    String aggregateId, Instant occurredAt, ProcurementSnapshotView snapshot)
    implements ProcurementEvent {}
