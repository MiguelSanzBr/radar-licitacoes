package br.dev.miguelsanzbr.radar.alerts.application.port;

import br.dev.miguelsanzbr.radar.alerts.domain.ProcurementFacts;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.time.Instant;
import java.util.List;

/** Finds the active saved searches, of verified and not erased subscribers, that match. */
public interface ReverseSearch {

  List<SearchMatch> find(ProcurementFacts procurement, Instant now);
}
