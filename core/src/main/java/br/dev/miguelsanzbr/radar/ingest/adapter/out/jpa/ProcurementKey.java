package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import java.io.Serializable;
import java.time.Instant;

/** Id class of the partitioned table: the partition key has to be part of the primary key. */
record ProcurementKey(String numeroControlePncp, Instant publishedAt) implements Serializable {}
