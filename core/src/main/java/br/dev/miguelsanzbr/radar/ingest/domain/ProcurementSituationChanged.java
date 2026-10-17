package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Instant;

public record ProcurementSituationChanged(
    String aggregateId,
    Instant occurredAt,
    PncpSituation from,
    PncpSituation to,
    Instant pncpUpdatedAt,
    boolean unexpected)
    implements ProcurementEvent {}
