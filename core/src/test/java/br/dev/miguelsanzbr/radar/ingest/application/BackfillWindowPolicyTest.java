package br.dev.miguelsanzbr.radar.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BackfillWindowPolicyTest {

  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  /** Sao Paulo is UTC-3 all year (no daylight saving since 2019). */
  private static Instant local(String time) {
    return Instant.parse("2026-10-22T" + time + ":00-03:00");
  }

  private static final BackfillWindowPolicy NIGHT =
      new BackfillWindowPolicy(LocalTime.of(0, 0), LocalTime.of(6, 0), SAO_PAULO);

  @Test
  void midnightIsInsideAndSixIsOutside() {
    assertThat(NIGHT.allowed(local("00:00"))).isTrue();
    assertThat(NIGHT.allowed(local("05:59"))).isTrue();
    assertThat(NIGHT.allowed(local("06:00"))).isFalse();
    assertThat(NIGHT.allowed(local("12:00"))).isFalse();
    assertThat(NIGHT.allowed(local("23:59"))).isFalse();
  }

  @Test
  void theZoneDecidesNotUtc() {
    // 02:00 UTC is 23:00 of the previous day in Sao Paulo: outside the night window
    assertThat(NIGHT.allowed(Instant.parse("2026-10-22T02:00:00Z"))).isFalse();
    // 05:00 UTC is 02:00 in Sao Paulo: inside
    assertThat(NIGHT.allowed(Instant.parse("2026-10-22T05:00:00Z"))).isTrue();
  }

  @Test
  void aWindowCrossingMidnightCoversBothSidesOfIt() {
    var crossing = new BackfillWindowPolicy(LocalTime.of(22, 0), LocalTime.of(6, 0), SAO_PAULO);

    assertThat(crossing.allowed(local("22:00"))).isTrue();
    assertThat(crossing.allowed(local("23:59"))).isTrue();
    assertThat(crossing.allowed(local("00:00"))).isTrue();
    assertThat(crossing.allowed(local("05:59"))).isTrue();
    assertThat(crossing.allowed(local("06:00"))).isFalse();
    assertThat(crossing.allowed(local("21:59"))).isFalse();
    assertThat(crossing.allowed(local("12:00"))).isFalse();
  }

  @Test
  void aWindowInsideTheDayExcludesTheHoursBeforeItsStart() {
    var day = new BackfillWindowPolicy(LocalTime.of(8, 0), LocalTime.of(18, 0), SAO_PAULO);

    assertThat(day.allowed(local("07:59"))).isFalse();
    assertThat(day.allowed(local("08:00"))).isTrue();
    assertThat(day.allowed(local("17:59"))).isTrue();
    assertThat(day.allowed(local("18:00"))).isFalse();
  }
}
