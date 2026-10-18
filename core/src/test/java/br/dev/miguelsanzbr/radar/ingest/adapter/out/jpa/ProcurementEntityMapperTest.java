package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.bare;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.discovered;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.domain.OrganUnit;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.ProposalWindow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcurementEntityMapperTest {

  private static PncpSnapshot with(PncpSnapshot s, OrganUnit unit, ProposalWindow window) {
    return new PncpSnapshot(
        s.id(),
        s.situation(),
        s.organ(),
        unit,
        s.modality(),
        s.disputeMode(),
        s.legalBasis(),
        s.srp(),
        s.estimatedValue(),
        s.awardedValue(),
        window,
        s.publishedAt(),
        s.pncpUpdatedAt(),
        s.objectDescription(),
        s.rawJson());
  }

  private static List<SituationHistoryEntity> rowsOf(Procurement p) {
    return p.history().stream()
        .map(h -> ProcurementEntityMapper.toEntity(p.id().value(), h))
        .toList();
  }

  private static void roundTrips(PncpSnapshot s) {
    var original = discovered(s);
    var entity = ProcurementEntityMapper.toEntity(original);
    entity.version = 0;

    assertThat(ProcurementEntityMapper.toDomain(entity, rowsOf(original)))
        .usingRecursiveComparison()
        .isEqualTo(original);
  }

  @Test
  void roundTripsAFullyPopulatedNotice() {
    roundTrips(snapshot(id(1)));
  }

  @Test
  void roundTripsANoticeWithEveryOptionalFieldAbsent() {
    roundTrips(bare(id(2)));
  }

  @Test
  void roundTripsAWindowWithOnlyOneBound() {
    var full = snapshot(id(3));
    var opening = Instant.parse("2026-10-23T12:00:00Z");

    roundTrips(with(full, full.unit(), new ProposalWindow(opening, null)));
    roundTrips(with(full, full.unit(), new ProposalWindow(null, opening)));
  }

  @Test
  void roundTripsALocationMadeOnlyOfUf() {
    var full = snapshot(id(4));
    var unit = new OrganUnit("1", "Unidade", full.unit().uf(), null, null);

    roundTrips(with(full, unit, full.proposalWindow()));
  }

  @Test
  void statusColumnHoldsTheUpperCaseStatusName() {
    var entity = ProcurementEntityMapper.toEntity(discovered(snapshot(id(5))));

    assertThat(entity.status).isEqualTo("PUBLISHED");
    assertThat(entity.version).isNull();
  }

  @Test
  void aStatusColumnThatContradictsTheSituationIsRejected() {
    var entity = ProcurementEntityMapper.toEntity(discovered(snapshot(id(7))));
    entity.version = 0;
    entity.status = "SUSPENDED";

    assertThatThrownBy(() -> ProcurementEntityMapper.toDomain(entity, List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SUSPENDED");
  }

  @Test
  void eachProcurementGetsOnlyItsOwnHistoryRowsInTheOrderGiven() {
    var first = discovered(snapshot(id(8)));
    var second = discovered(snapshot(id(9)));
    var firstEntity = ProcurementEntityMapper.toEntity(first);
    var secondEntity = ProcurementEntityMapper.toEntity(second);
    firstEntity.version = 0;
    secondEntity.version = 0;
    var entry = first.history().getFirst();
    var rows =
        List.of(
            ProcurementEntityMapper.toEntity(id(9).value(), entry),
            ProcurementEntityMapper.toEntity(id(8).value(), entry),
            ProcurementEntityMapper.toEntity(id(8).value(), entry));

    var restored = ProcurementEntityMapper.toDomain(List.of(firstEntity, secondEntity), rows);

    assertThat(restored).extracting(p -> p.history().size()).containsExactly(2, 1);
    assertThat(restored).extracting(Procurement::id).containsExactly(id(8), id(9));
  }

  @Test
  void historyEntryMapsBothWays() {
    var original = discovered(snapshot(id(6))).history().getFirst();

    var entity = ProcurementEntityMapper.toEntity(id(6).value(), original);

    assertThat(entity.fromSituationId).isNull();
    assertThat(ProcurementEntityMapper.toDomain(List.of(entity))).containsExactly(original);
  }
}
