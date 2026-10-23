package br.dev.miguelsanzbr.radar.architecture.fixture.ingest;

import br.dev.miguelsanzbr.radar.architecture.fixture.search.SearchOnItsOwn;

/** Violates the rule that ingest never depends on search. */
public class IngestReadsSearch {

  SearchOnItsOwn search;
}
