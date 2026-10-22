package br.dev.miguelsanzbr.radar.ingest.domain;

import java.util.Locale;

/**
 * Internal status, mapped from the PNCP situation id (working assumption, confirmed by probe S10).
 */
public sealed interface ProcurementStatus {

  record Published() implements ProcurementStatus {
    @Override
    public String name() {
      return "Published";
    }
  }

  record Suspended() implements ProcurementStatus {
    @Override
    public String name() {
      return "Suspended";
    }
  }

  record Revoked() implements ProcurementStatus {
    @Override
    public String name() {
      return "Revoked";
    }
  }

  record Annulled() implements ProcurementStatus {
    @Override
    public String name() {
      return "Annulled";
    }
  }

  static ProcurementStatus fromSituationId(int id) {
    return switch (id) {
      case 1 -> new Published();
      case 2 -> new Revoked();
      case 3 -> new Annulled();
      case 4 -> new Suspended();
      default -> throw new IllegalArgumentException("unknown pncp situation id [" + id + "]");
    };
  }

  String name();

  /** Upper-case form used by events and by the stored column: PUBLISHED, SUSPENDED, ... */
  default String code() {
    return name().toUpperCase(Locale.ROOT);
  }

  /** Transitions of the state machine; Revoked and Annulled are terminal. */
  default boolean allowsTransitionTo(ProcurementStatus next) {
    var toTerminal = next instanceof Revoked || next instanceof Annulled;
    if (this instanceof Published) {
      return next instanceof Suspended || toTerminal;
    }
    if (this instanceof Suspended) {
      return next instanceof Published || toTerminal;
    }
    return false;
  }
}
