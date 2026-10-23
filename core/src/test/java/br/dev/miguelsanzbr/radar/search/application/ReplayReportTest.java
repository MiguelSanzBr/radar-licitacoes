package br.dev.miguelsanzbr.radar.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReplayReportTest {

  private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");

  @Test
  void ratePerSecondIsEventsOverTheElapsedTime() {
    var report = ReplayReport.of(50_000, START, START.plusSeconds(25));

    assertThat(report.events()).isEqualTo(50_000);
    assertThat(report.duration()).isEqualTo(Duration.ofSeconds(25));
    assertThat(report.eventsPerSecond()).isEqualTo(2000.0);
    assertThat(report.startedAt()).isEqualTo(START);
    assertThat(report.finishedAt()).isEqualTo(START.plusSeconds(25));
  }

  @Test
  void aSubSecondRunKeepsItsFraction() {
    var report = ReplayReport.of(300, START, START.plusMillis(500));

    assertThat(report.eventsPerSecond()).isEqualTo(600.0);
  }

  @Test
  void zeroDurationDoesNotDivideByZero() {
    var report = ReplayReport.of(10, START, START);

    assertThat(report.duration()).isZero();
    assertThat(report.eventsPerSecond()).isZero();
  }
}
