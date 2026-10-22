package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import br.dev.miguelsanzbr.radar.ingest.domain.ContentHash;
import br.dev.miguelsanzbr.radar.ingest.domain.DisputeMode;
import br.dev.miguelsanzbr.radar.ingest.domain.LegalBasis;
import br.dev.miguelsanzbr.radar.ingest.domain.Modality;
import br.dev.miguelsanzbr.radar.ingest.domain.Organ;
import br.dev.miguelsanzbr.radar.ingest.domain.OrganUnit;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSituation;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.ProposalWindow;
import br.dev.miguelsanzbr.radar.ingest.domain.SituationHistoryEntry;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Entity to aggregate and back; the aggregate never sees JPA types. */
final class ProcurementEntityMapper {

  private ProcurementEntityMapper() {}

  private static <T, R> R mapIfPresent(T value, Function<T, R> mapper) {
    return value == null ? null : mapper.apply(value);
  }

  /** New entity (no version yet); the history rows are separate entities. */
  static ProcurementEntity toEntity(Procurement p) {
    var entity = new ProcurementEntity(new ProcurementKey(p.id().value(), p.publishedAt()));
    copyState(p, entity);
    return entity;
  }

  /** Copies every mutable column of the aggregate; key and version are left alone. */
  static void copyState(Procurement p, ProcurementEntity e) {
    e.contentHash = p.contentHash().value();
    e.pncpSituationId = p.situation().id();
    e.pncpSituationName = p.situation().name();
    e.status = statusColumn(p);
    e.objectDescription = p.objectDescription();
    e.organCnpj = p.organ().cnpj();
    e.organName = p.organ().name();
    e.unitCode = p.unit().code();
    e.unitName = p.unit().name();
    e.uf = mapIfPresent(p.unit().uf(), Uf::name);
    e.municipalityName = p.unit().municipalityName();
    e.ibgeCode = mapIfPresent(p.unit().ibgeCode(), IbgeCode::value);
    e.modalityId = p.modality().id();
    e.modalityName = p.modality().name();
    e.disputeModeId = mapIfPresent(p.disputeMode(), DisputeMode::id);
    e.disputeModeName = mapIfPresent(p.disputeMode(), DisputeMode::name);
    e.legalBasisId = mapIfPresent(p.legalBasis(), LegalBasis::code);
    e.legalBasisName = mapIfPresent(p.legalBasis(), LegalBasis::name);
    e.srp = p.srp();
    e.estimatedValue = mapIfPresent(p.estimatedValue(), Money::amount);
    e.awardedValue = mapIfPresent(p.awardedValue(), Money::amount);
    e.proposalOpeningAt = mapIfPresent(p.proposalWindow(), ProposalWindow::openingAt);
    e.proposalClosingAt = mapIfPresent(p.proposalWindow(), ProposalWindow::closingAt);
    e.pncpUpdatedAt = p.pncpUpdatedAt();
    e.closedEmittedAt = p.closedEmittedAt();
    e.raw = p.rawJson();
    e.firstSeenAt = p.firstSeenAt();
    e.lastSeenAt = p.lastSeenAt();
    e.revision = p.revision();
  }

  static SituationHistoryEntity toEntity(String numeroControlePncp, SituationHistoryEntry entry) {
    return new SituationHistoryEntity(
        numeroControlePncp,
        entry.fromSituationId(),
        entry.toSituationId(),
        entry.unexpected(),
        entry.pncpUpdatedAt(),
        entry.recordedAt());
  }

  static List<SituationHistoryEntry> toDomain(List<SituationHistoryEntity> rows) {
    return rows.stream()
        .map(
            r ->
                new SituationHistoryEntry(
                    r.fromSituationId,
                    r.toSituationId,
                    r.unexpected,
                    r.pncpUpdatedAt,
                    r.recordedAt))
        .toList();
  }

  /** Restores each entity with its own rows from {@code rows}, which are already in order. */
  static List<Procurement> toDomain(
      List<ProcurementEntity> entities, List<SituationHistoryEntity> rows) {
    var byNumber = rows.stream().collect(Collectors.groupingBy(r -> r.numeroControlePncp));
    return entities.stream()
        .map(e -> toDomain(e, byNumber.getOrDefault(e.numeroControlePncp, List.of())))
        .toList();
  }

  static Procurement toDomain(ProcurementEntity e, List<SituationHistoryEntity> rows) {
    var unit =
        new OrganUnit(
            e.unitCode,
            e.unitName,
            mapIfPresent(e.uf, Uf::valueOf),
            e.municipalityName,
            mapIfPresent(e.ibgeCode, IbgeCode::new));
    var window =
        e.proposalOpeningAt == null && e.proposalClosingAt == null
            ? null
            : new ProposalWindow(e.proposalOpeningAt, e.proposalClosingAt);
    var snapshot =
        new PncpSnapshot(
            new ProcurementId(e.numeroControlePncp),
            new PncpSituation(e.pncpSituationId, e.pncpSituationName),
            new Organ(e.organCnpj, e.organName),
            unit,
            new Modality(e.modalityId, e.modalityName),
            mapIfPresent(e.disputeModeId, id -> new DisputeMode(id, e.disputeModeName)),
            mapIfPresent(e.legalBasisId, code -> new LegalBasis(code, e.legalBasisName)),
            e.srp,
            mapIfPresent(e.estimatedValue, Money::new),
            mapIfPresent(e.awardedValue, Money::new),
            window,
            e.publishedAt,
            e.pncpUpdatedAt,
            e.objectDescription,
            e.raw);
    var procurement = restore(snapshot, e, rows);
    if (!statusColumn(procurement).equals(e.status)) {
      throw new IllegalStateException(
          "status [%s] does not match situation %d of %s"
              .formatted(e.status, e.pncpSituationId, e.numeroControlePncp));
    }
    return procurement;
  }

  /** The status is derived from the situation; the column only exists to index on it. */
  private static String statusColumn(Procurement p) {
    return p.status().code();
  }

  /** The only call to the aggregate's rehydration factory. */
  private static Procurement restore(
      PncpSnapshot s, ProcurementEntity e, List<SituationHistoryEntity> rows) {
    return Procurement.restore(
        s,
        new ContentHash(e.contentHash),
        e.version,
        toDomain(rows),
        e.firstSeenAt,
        e.lastSeenAt,
        e.closedEmittedAt,
        e.revision);
  }
}
