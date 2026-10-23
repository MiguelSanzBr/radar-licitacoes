package br.dev.miguelsanzbr.radar.architecture.fixture.search;

import br.dev.miguelsanzbr.radar.architecture.fixture.outbox.OutboxInternals;

/** Violates the rule that search never depends on outbox. */
public class SearchReadsOutbox {

  OutboxInternals internals;
}
