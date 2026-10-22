package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code ingest.ingestion_run}; fields are package-private for the mapper next to it. */
@Entity
@Table(schema = "ingest", name = "ingestion_run")
class IngestionRunEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  /** Null until first persisted; Hibernate owns it afterwards. */
  @Version Integer version;

  String kind;

  @JdbcTypeCode(SqlTypes.SMALLINT)
  int modalityId;

  Instant windowStart;
  Instant windowEnd;
  int cursorPage;
  String status;
  int fetched;
  int discovered;
  int updated;
  int unchanged;
  Instant startedAt;
  Instant finishedAt;
  String error;

  protected IngestionRunEntity() {}
}
