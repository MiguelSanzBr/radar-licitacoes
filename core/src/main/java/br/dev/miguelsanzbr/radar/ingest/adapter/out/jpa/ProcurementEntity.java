package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** Row of {@code ingest.procurement}; fields are package-private for the mapper next to it. */
@Entity
@IdClass(ProcurementKey.class)
@Table(schema = "ingest", name = "procurement")
class ProcurementEntity {

  @Id
  @Column(name = "numero_controle_pncp")
  String numeroControlePncp;

  @Id Instant publishedAt;

  byte[] contentHash;

  /** Null until first persisted; Hibernate owns it afterwards. */
  @Version Integer version;

  @JdbcTypeCode(SqlTypes.SMALLINT)
  int pncpSituationId;

  String pncpSituationName;
  String status;
  String objectDescription;

  @JdbcTypeCode(SqlTypes.CHAR)
  String organCnpj;

  String organName;
  String unitCode;
  String unitName;

  @JdbcTypeCode(SqlTypes.CHAR)
  String uf;

  String municipalityName;

  @JdbcTypeCode(SqlTypes.CHAR)
  String ibgeCode;

  @JdbcTypeCode(SqlTypes.SMALLINT)
  int modalityId;

  String modalityName;

  @JdbcTypeCode(SqlTypes.SMALLINT)
  Integer disputeModeId;

  String disputeModeName;
  Integer legalBasisId;
  String legalBasisName;
  boolean srp;
  BigDecimal estimatedValue;

  /**
   * Written only by the dual write trigger (V14); Hibernate reads back what the trigger set after
   * each insert and update, so a just saved notice already carries its cents.
   */
  @Column(name = "estimated_value_cents", insertable = false, updatable = false)
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  Long estimatedValueCents;

  BigDecimal awardedValue;
  Instant proposalOpeningAt;
  Instant proposalClosingAt;
  Instant pncpUpdatedAt;
  Instant closedEmittedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  String raw;

  Instant firstSeenAt;

  /** A heartbeat (snapshot seen again, unchanged) must not bump the lock version. */
  @OptimisticLock(excluded = true)
  Instant lastSeenAt;

  long revision;

  protected ProcurementEntity() {}

  ProcurementEntity(ProcurementKey key) {
    this.numeroControlePncp = key.numeroControlePncp();
    this.publishedAt = key.publishedAt();
  }

  ProcurementKey key() {
    return new ProcurementKey(numeroControlePncp, publishedAt);
  }
}
