package br.dev.miguelsanzbr.radar.ops.domain;

import java.time.Instant;

/** Where a projector of the read model stopped; the offset is null before its first event. */
public record Checkpoint(String projector, Long lastOffset, Instant updatedAt) {}
