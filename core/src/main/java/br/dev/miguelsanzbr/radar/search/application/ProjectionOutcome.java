package br.dev.miguelsanzbr.radar.search.application;

/** What the store did with an event. */
public enum ProjectionOutcome {
  /** Applied, or ignored by the version guard; either way it is recorded as processed. */
  APPLIED,
  /** Seen before: nothing was written. */
  DUPLICATE,
  /** Its procurement is not projected yet: nothing was written, not even the inbox row. */
  DEFERRED
}
