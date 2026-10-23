package br.dev.miguelsanzbr.radar.architecture.fixture.application;

import br.dev.miguelsanzbr.radar.architecture.fixture.adapter.AnAdapter;

/** Violates the rule that application never depends on an adapter. */
public class ReachesAnAdapter {

  AnAdapter adapter;
}
