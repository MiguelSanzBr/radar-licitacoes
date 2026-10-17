package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Instant;

public record ProcurementClosed(String aggregateId, Instant occurredAt, Instant closingAt)
    implements ProcurementEvent {}
