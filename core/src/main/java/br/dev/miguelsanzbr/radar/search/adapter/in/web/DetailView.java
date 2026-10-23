package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import br.dev.miguelsanzbr.radar.search.application.SituationChange;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** The detail page's data as final text. A null field is one the portal did not give. */
record DetailView(
    String title,
    String organ,
    String cnpj,
    String unit,
    String place,
    String ibge,
    String modality,
    String disputeMode,
    String legalBasis,
    boolean srp,
    String status,
    String phase,
    String estimatedValue,
    String awardedValue,
    String opening,
    String closing,
    String published,
    String updated,
    List<Change> history,
    String pncpUrl) {

  /** One step of the history; {@code from} is null for the first observation. */
  record Change(String from, String to, String at, boolean unexpected) {}

  // CNPJ-1-SEQUENCE/YEAR; the portal's page for the notice takes the sequence without zeros
  private static final Pattern CONTROL_NUMBER =
      Pattern.compile("(\\d{14})-\\d+-(\\d{1,9})/(\\d{4})");

  static DetailView of(ProcurementDetail detail, Instant now) {
    return new DetailView(
        detail.objectDescription(),
        detail.organName(),
        detail.organCnpj(),
        detail.unitName(),
        Labels.place(detail.municipalityName(), detail.uf()),
        detail.ibgeCode(),
        detail.modalityName() == null
            ? Labels.modality(detail.modalityId())
            : detail.modalityName(),
        detail.disputeMode(),
        detail.legalBasis(),
        detail.srp(),
        Labels.status(detail.status()),
        Labels.phase(detail.proposalOpeningAt(), detail.proposalClosingAt(), now),
        Labels.money(detail.estimatedValue()),
        Labels.money(detail.awardedValue()),
        Labels.when(detail.proposalOpeningAt()),
        Labels.when(detail.proposalClosingAt()),
        Labels.when(detail.publishedAt()),
        Labels.when(detail.pncpUpdatedAt()),
        detail.history().stream().map(DetailView::change).toList(),
        pncpUrl(detail.id()));
  }

  private static Change change(SituationChange change) {
    return new Change(
        change.from(), change.to(), Labels.when(change.pncpUpdatedAt()), change.unexpected());
  }

  /**
   * Null when the id is not a control number, so the page shows no link rather than a wrong one.
   */
  static String pncpUrl(String id) {
    var match = CONTROL_NUMBER.matcher(id);
    return match.matches()
        ? "https://pncp.gov.br/app/editais/%s/%s/%d"
            .formatted(match.group(1), match.group(3), Integer.parseInt(match.group(2)))
        : null;
  }
}
