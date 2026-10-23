package br.dev.miguelsanzbr.radar.search.application;

import java.time.Instant;

/** One recorded change of situation; {@code from} is null on the first observation. */
public record SituationChange(String from, String to, Instant pncpUpdatedAt, boolean unexpected) {}
