package br.dev.miguelsanzbr.radar.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.PartitionCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PartitionMaintenanceTest {

  /** Remembers the months it was asked to create, in order. */
  private static final class FakeCatalog implements PartitionCatalog {
    final Set<YearMonth> existing = new TreeSet<>();
    final List<YearMonth> ensured = new ArrayList<>();
    YearMonth failOn;

    @Override
    public void ensure(YearMonth month) {
      if (month.equals(failOn)) {
        throw new IllegalStateException("permission denied for schema ingest");
      }
      ensured.add(month);
      existing.add(month);
    }

    @Override
    public boolean exists(YearMonth month) {
      return existing.contains(month);
    }
  }

  private final FakeCatalog catalog = new FakeCatalog();

  private PartitionMaintenance at(String instant, int monthsAhead) {
    return new PartitionMaintenance(
        catalog, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), monthsAhead);
  }

  @Test
  void ensuresTheCurrentMonthAndTheNextOnes() {
    at("2026-10-22T12:00:00Z", 3).ensure();

    assertThat(catalog.ensured)
        .containsExactly(
            YearMonth.of(2026, 10),
            YearMonth.of(2026, 11),
            YearMonth.of(2026, 12),
            YearMonth.of(2027, 1));
  }

  @Test
  void theMonthArithmeticRollsOverDecemberIntoJanuary() {
    at("2027-12-31T23:59:59Z", 2).ensure();

    assertThat(catalog.ensured)
        .containsExactly(YearMonth.of(2027, 12), YearMonth.of(2028, 1), YearMonth.of(2028, 2));
  }

  @Test
  void theMonthIsReadInUtcLikeThePartitionBounds() {
    // 22:00 in Sao Paulo on Nov 30 is already Dec 1 in UTC, which is where the bounds are cut
    at("2026-12-01T01:00:00Z", 1).ensure();

    assertThat(catalog.ensured).containsExactly(YearMonth.of(2026, 12), YearMonth.of(2027, 1));
  }

  @Test
  void ensuringTwiceAsksForTheSameMonthsAgain() {
    var maintenance = at("2026-10-22T12:00:00Z", 1);

    maintenance.ensure();
    maintenance.ensure();

    assertThat(catalog.ensured).hasSize(4);
  }

  @Test
  void aFailureStopsTheRunAndReachesTheCaller() {
    catalog.failOn = YearMonth.of(2026, 11);

    assertThatThrownBy(() -> at("2026-10-22T12:00:00Z", 3).ensure())
        .isInstanceOf(IllegalStateException.class);
    assertThat(catalog.ensured).containsExactly(YearMonth.of(2026, 10));
  }

  @Test
  void monthsAheadCountsTheFutureMonthsThatExistUpToTheHorizon() {
    var maintenance = at("2026-10-22T12:00:00Z", 3);
    catalog.existing.addAll(
        List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12)));

    assertThat(maintenance.monthsAhead()).isEqualTo(2);

    catalog.existing.addAll(List.of(YearMonth.of(2027, 1), YearMonth.of(2027, 2)));

    assertThat(maintenance.monthsAhead()).isEqualTo(3);
  }

  @Test
  void aMissingCurrentMonthIsZeroEvenWhenTheFutureMonthsExist() {
    catalog.existing.addAll(
        List.of(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1)));

    assertThat(at("2026-10-22T12:00:00Z", 3).monthsAhead()).isZero();
  }

  @Test
  void aMissingNextMonthIsZeroEvenWhenLaterMonthsExist() {
    catalog.existing.addAll(List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 12)));

    assertThat(at("2026-10-22T12:00:00Z", 3).monthsAhead()).isZero();
  }

  @Test
  void afterEnsureTheHorizonIsFullyCovered() {
    var maintenance = at("2027-12-15T00:00:00Z", 3);
    assertThat(maintenance.monthsAhead()).isZero();

    maintenance.ensure();

    assertThat(maintenance.monthsAhead()).isEqualTo(3);
  }

  @Test
  void theHorizonMustBeAtLeastOneMonth() {
    assertThatThrownBy(() -> at("2026-10-22T12:00:00Z", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("monthsAhead");
  }
}
