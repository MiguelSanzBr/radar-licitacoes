package br.dev.miguelsanzbr.radar.ingest.application.port;

import java.time.YearMonth;

/** The monthly partitions of {@code ingest.procurement}, bounded in UTC. */
public interface PartitionCatalog {

  /** Creates the partition of the month unless it exists. */
  void ensure(YearMonth month);

  boolean exists(YearMonth month);
}
