package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Instant;

/** Proposal receiving window; either bound may be missing. */
public record ProposalWindow(Instant openingAt, Instant closingAt) {

  public ProposalWindow {
    if (openingAt != null && closingAt != null && closingAt.isBefore(openingAt)) {
      throw new IllegalArgumentException(
          "closingAt " + closingAt + " before openingAt " + openingAt);
    }
  }

  /** Closing is checked first, so an empty window is CLOSED once reached. */
  public ProposalPhase phaseAt(Instant now) {
    if (openingAt == null && closingAt == null) {
      return ProposalPhase.UNKNOWN;
    }
    if (closingAt != null && !now.isBefore(closingAt)) {
      return ProposalPhase.CLOSED;
    }
    if (openingAt != null && now.isBefore(openingAt)) {
      return ProposalPhase.UPCOMING;
    }
    return ProposalPhase.OPEN;
  }
}
