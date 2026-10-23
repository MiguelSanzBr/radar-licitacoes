package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.PartitionCatalog;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Keeps {@code ingest.procurement} ahead of the calendar: there is no DEFAULT partition by design,
 * so a notice published in a month without one fails its insert (docs/runbook.md#particao-ausente).
 */
public final class PartitionMaintenance {

  private final PartitionCatalog catalog;
  private final Clock clock;
  private final int monthsAhead;

  public PartitionMaintenance(PartitionCatalog catalog, Clock clock, int monthsAhead) {
    if (monthsAhead < 1) {
      throw new IllegalArgumentException("monthsAhead must be >= 1");
    }
    this.catalog = catalog;
    this.clock = clock;
    this.monthsAhead = monthsAhead;
  }

  /** Creates the current month and the next {@code monthsAhead}; stops at the first failure. */
  public void ensure() {
    var current = currentMonth();
    for (var i = 0; i <= monthsAhead; i++) {
      catalog.ensure(current.plusMonths(i));
    }
  }

  /**
   * How many months after the current one exist in a row, up to {@code monthsAhead}. A gap in the
   * current month or right after it is 0 whatever comes later, because the insert of that month
   * fails.
   */
  public int monthsAhead() {
    var current = currentMonth();
    if (!catalog.exists(current)) {
      return 0;
    }
    var ahead = 0;
    while (ahead < monthsAhead && catalog.exists(current.plusMonths(ahead + 1L))) {
      ahead++;
    }
    return ahead;
  }

  /** UTC, like the partition bounds. */
  private YearMonth currentMonth() {
    return YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC));
  }
}
