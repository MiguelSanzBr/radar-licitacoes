package br.dev.miguelsanzbr.radar.ops.domain;

import java.time.Instant;

/**
 * The last run of a kind of collection for a modality; {@code finishedAt} is null while it runs.
 */
public record IngestionRun(
    String kind,
    int modalityId,
    String status,
    int fetched,
    int discovered,
    int updated,
    int unchanged,
    Instant startedAt,
    Instant finishedAt) {}
