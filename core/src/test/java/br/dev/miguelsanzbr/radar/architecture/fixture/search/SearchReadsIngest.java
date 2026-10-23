package br.dev.miguelsanzbr.radar.architecture.fixture.search;

import br.dev.miguelsanzbr.radar.architecture.fixture.ingest.IngestInternals;

/** Violates the rule that search never depends on ingest. */
public class SearchReadsIngest {

  IngestInternals internals;
}
