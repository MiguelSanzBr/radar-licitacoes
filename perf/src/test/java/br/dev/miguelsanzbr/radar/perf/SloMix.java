package br.dev.miguelsanzbr.radar.perf;

import io.gatling.javaapi.core.Simulation;

/**
 * The mixed profile of {@code make slo}: S1, S2, S5 and S6 together at low steady rates, for the
 * measured duration of {@code -Dbench.measured-seconds} (after the warm-up of {@link Bench}). The
 * users of each scenario are {@code -Dbench.users.<id>}; the defaults here are far below the plan's
 * numbers for {@code make bench}, because the point is the SLO under realistic traffic while the
 * ingestion runs, not the saturation.
 */
public class SloMix extends Simulation {

  public SloMix() {
    setUp(
            S1TextSearch.population(Bench.users("s1", 5)),
            S2FilteredSearch.population(Bench.users("s2", 5)),
            S5Detail.population(Bench.users("s5", 10)),
            S6Stats.population(Bench.users("s6", 2)))
        .protocols(Bench.protocol());
  }
}
