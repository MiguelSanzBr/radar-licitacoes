package br.dev.miguelsanzbr.radar.search.application;

import java.util.List;

/** Read side port of the stats, answered from the pre-aggregated table. */
public interface StatsReadModel {

  /** The groups ordered by key, never empty rows. */
  List<StatsRow> aggregate(GroupBy groupBy);
}
