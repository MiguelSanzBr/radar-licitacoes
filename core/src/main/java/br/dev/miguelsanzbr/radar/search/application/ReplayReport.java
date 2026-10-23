package br.dev.miguelsanzbr.radar.search.application;

import java.time.Duration;
import java.time.Instant;

/** What a replay did: how many records it read and how long it took. */
public record ReplayReport(
    long events, Duration duration, double eventsPerSecond, Instant startedAt, Instant finishedAt) {

  public static ReplayReport of(long events, Instant startedAt, Instant finishedAt) {
    var duration = Duration.between(startedAt, finishedAt);
    var seconds = duration.toNanos() / 1e9;
    return new ReplayReport(
        events, duration, seconds == 0 ? 0 : events / seconds, startedAt, finishedAt);
  }
}
