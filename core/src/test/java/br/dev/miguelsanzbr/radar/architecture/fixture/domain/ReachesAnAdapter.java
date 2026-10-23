package br.dev.miguelsanzbr.radar.architecture.fixture.domain;

import br.dev.miguelsanzbr.radar.architecture.fixture.adapter.AnAdapter;

/** Violates the rule that domain never depends on an adapter. */
public class ReachesAnAdapter {

  AnAdapter adapter;
}
