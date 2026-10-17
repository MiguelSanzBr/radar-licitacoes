package br.dev.miguelsanzbr.radar.ingest.domain;

/** Derived from the proposal window and the clock; never stored. */
public enum ProposalPhase {
  UPCOMING,
  OPEN,
  CLOSED,
  UNKNOWN
}
