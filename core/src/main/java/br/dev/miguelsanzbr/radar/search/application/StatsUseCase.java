package br.dev.miguelsanzbr.radar.search.application;

import java.util.List;
import java.util.Objects;

/** Procurement counts and estimated sums per uf, modality, month or value band. */
public class StatsUseCase {

  private final StatsReadModel readModel;

  public StatsUseCase(StatsReadModel readModel) {
    this.readModel = readModel;
  }

  public List<StatsRow> stats(GroupBy groupBy) {
    return readModel.aggregate(Objects.requireNonNull(groupBy, "groupBy"));
  }
}
