package br.dev.miguelsanzbr.radar.alerts.domain;

import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A saved search matched a procurement for the first time. Carries what the e-mail shows, so the
 * notifier needs no other lookup.
 */
public record AlertMatched(
    UUID savedSearchId,
    UUID subscriberId,
    String procurementId,
    Frequency frequency,
    String objectDescription,
    String organName,
    String uf,
    String municipality,
    Money estimatedValue,
    Instant proposalClosingAt,
    String detailPath) {

  /** Characters of the object description kept for the e-mail. */
  public static final int EXCERPT_LENGTH = 300;

  public static AlertMatched of(SearchMatch match, ProcurementFacts procurement) {
    return new AlertMatched(
        match.savedSearchId(),
        match.subscriberId(),
        procurement.id(),
        match.frequency(),
        excerpt(procurement.objectDescription()),
        procurement.organName(),
        procurement.uf(),
        procurement.municipality(),
        procurement.estimatedValue(),
        procurement.closingAt(),
        // why: the control number has a slash; the API writes it as a dash in paths
        "/licitacoes/" + procurement.id().replace('/', '-'));
  }

  /** Cuts at a character boundary, never inside a surrogate pair. */
  private static String excerpt(String text) {
    if (text.codePointCount(0, text.length()) <= EXCERPT_LENGTH) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, EXCERPT_LENGTH));
  }
}
