package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Insert-only row of the situation history; the owning procurement sets the join column. */
@Entity
@Table(schema = "ingest", name = "procurement_situation_history")
class SituationHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(name = "numero_controle_pncp")
  String numeroControlePncp;

  @JdbcTypeCode(SqlTypes.SMALLINT)
  Integer fromSituationId;

  @JdbcTypeCode(SqlTypes.SMALLINT)
  int toSituationId;

  boolean unexpected;
  Instant pncpUpdatedAt;
  Instant recordedAt;

  protected SituationHistoryEntity() {}

  SituationHistoryEntity(
      String numeroControlePncp,
      Integer fromSituationId,
      int toSituationId,
      boolean unexpected,
      Instant pncpUpdatedAt,
      Instant recordedAt) {
    this.numeroControlePncp = numeroControlePncp;
    this.fromSituationId = fromSituationId;
    this.toSituationId = toSituationId;
    this.unexpected = unexpected;
    this.pncpUpdatedAt = pncpUpdatedAt;
    this.recordedAt = recordedAt;
  }
}
