package br.dev.miguelsanzbr.radar.architecture.fixture.ingest;

import org.togglz.core.manager.FeatureManager;

/** Violates the rule that ingest reads flags only through its own port. */
public class IngestReadsTogglz {

  FeatureManager features;
}
