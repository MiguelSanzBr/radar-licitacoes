package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import br.dev.miguelsanzbr.radar.search.adapter.in.web.Snippets.Segment;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import java.time.Instant;
import java.util.List;

/** One hit as the list shows it: every field is final text, ready to be escaped and printed. */
record ResultRow(
    String path,
    String title,
    List<Segment> snippet,
    String organ,
    String place,
    String modality,
    String status,
    String phase,
    String value,
    String closing) {

  static ResultRow of(RankedResult hit, Instant now) {
    return new ResultRow(
        ProcurementIds.toPath(hit.id()),
        hit.objectDescription(),
        Snippets.split(hit.snippet()),
        hit.organName(),
        Labels.place(hit.municipalityName(), hit.uf()),
        Labels.modality(hit.modalityId()),
        Labels.status(hit.status()),
        Labels.phase(hit.proposalOpeningAt(), hit.proposalClosingAt(), now),
        Labels.money(hit.estimatedValue()),
        Labels.when(hit.proposalClosingAt()));
  }
}
