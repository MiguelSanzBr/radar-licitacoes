package br.dev.miguelsanzbr.radar.search.domain;

import java.time.Instant;

/** Where a procurement stands in its proposal window; derived from the clock, never stored. */
public enum ProposalPhase {
  UPCOMING,
  OPEN,
  CLOSED,
  UNKNOWN;

  /**
   * Closing is checked first, so a window that has ended is CLOSED whatever the opening says. An
   * opened window with no closing date is UNKNOWN, as the {@code open} filter does not list it.
   */
  public static ProposalPhase at(Instant openingAt, Instant closingAt, Instant now) {
    if (openingAt == null && closingAt == null) {
      return UNKNOWN;
    }
    if (closingAt != null && !now.isBefore(closingAt)) {
      return CLOSED;
    }
    if (openingAt != null && now.isBefore(openingAt)) {
      return UPCOMING;
    }
    // same as the open filter: only a known closing date makes a window open
    return closingAt == null ? UNKNOWN : OPEN;
  }
}
