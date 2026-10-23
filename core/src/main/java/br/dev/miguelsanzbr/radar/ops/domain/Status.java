package br.dev.miguelsanzbr.radar.ops.domain;

/** Ordered from healthy to broken, so the worst of a set is its maximum. */
public enum Status {
  UP,
  DEGRADED,
  DOWN
}
