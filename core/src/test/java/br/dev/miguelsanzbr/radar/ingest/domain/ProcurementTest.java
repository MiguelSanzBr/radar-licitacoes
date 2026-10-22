package br.dev.miguelsanzbr.radar.ingest.domain;

import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.ID;
import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.at;
import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Annulled;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Published;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Revoked;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Suspended;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProcurementTest {

  private static final Instant T1 = T0.plusSeconds(60);
  private static final Instant T2 = T0.plusSeconds(120);

  private static Procurement discovered() {
    return Procurement.discover(snapshot(), at(T0)).procurement();
  }

  private static PncpSnapshot withSituation(int id, String name) {
    return snapshot(b -> b.situation = new PncpSituation(id, name));
  }

  // ---- discover

  @Test
  void discoverEmitsExactlyOneDiscoveredEvent() {
    var snapshot = snapshot();
    var discovery = Procurement.discover(snapshot, at(T0));
    assertThat(discovery.events()).hasSize(1);
    var event = (ProcurementDiscovered) discovery.events().getFirst();
    assertThat(event.aggregateId()).isEqualTo(ID.value());
    assertThat(event.occurredAt()).isEqualTo(T0);
    assertThat(event.eventType()).isEqualTo("ProcurementDiscovered");
    var view = event.snapshot();
    assertThat(view.id()).isEqualTo(ID);
    assertThat(view.status()).isEqualTo("PUBLISHED");
    assertThat(view.situation()).isEqualTo(snapshot.situation());
    assertThat(view.organ()).isEqualTo(snapshot.organ());
    assertThat(view.unit()).isEqualTo(snapshot.unit());
    assertThat(view.modality()).isEqualTo(snapshot.modality());
    assertThat(view.disputeMode()).isEqualTo(snapshot.disputeMode());
    assertThat(view.legalBasis()).isEqualTo(snapshot.legalBasis());
    assertThat(view.srp()).isEqualTo(snapshot.srp());
    assertThat(view.estimatedValue()).isEqualTo(snapshot.estimatedValue());
    assertThat(view.awardedValue()).isEqualTo(snapshot.awardedValue());
    assertThat(view.proposalWindow()).isEqualTo(snapshot.proposalWindow());
    assertThat(view.publishedAt()).isEqualTo(snapshot.publishedAt());
    assertThat(view.pncpUpdatedAt()).isEqualTo(snapshot.pncpUpdatedAt());
    assertThat(view.objectDescription()).isEqualTo(snapshot.objectDescription());
    assertThat(view.revision()).isEqualTo(1);
  }

  @Test
  void discoveryCopiesItsEvents() {
    var discovery = Procurement.discover(snapshot(), at(T0));
    var source = new java.util.ArrayList<>(discovery.events());
    var copy = new Procurement.Discovery(discovery.procurement(), source);
    source.clear();
    assertThat(copy.events()).hasSize(1);
    assertThatThrownBy(() -> discovery.events().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void discoverInitialisesState() {
    var snapshot = snapshot();
    var p = Procurement.discover(snapshot, at(T0)).procurement();
    assertThat(p.id()).isEqualTo(ID);
    assertThat(p.contentHash()).isEqualTo(ContentHash.of(snapshot));
    assertThat(p.version()).isZero();
    assertThat(p.revision()).isEqualTo(1);
    assertThat(p.status()).isEqualTo(new Published());
    assertThat(p.situation()).isEqualTo(snapshot.situation());
    assertThat(p.organ()).isEqualTo(snapshot.organ());
    assertThat(p.unit()).isEqualTo(snapshot.unit());
    assertThat(p.modality()).isEqualTo(snapshot.modality());
    assertThat(p.disputeMode()).isEqualTo(snapshot.disputeMode());
    assertThat(p.legalBasis()).isEqualTo(snapshot.legalBasis());
    assertThat(p.srp()).isFalse();
    assertThat(p.estimatedValue()).isEqualTo(snapshot.estimatedValue());
    assertThat(p.awardedValue()).isEqualTo(snapshot.awardedValue());
    assertThat(p.proposalWindow()).isEqualTo(snapshot.proposalWindow());
    assertThat(p.publishedAt()).isEqualTo(snapshot.publishedAt());
    assertThat(p.pncpUpdatedAt()).isEqualTo(snapshot.pncpUpdatedAt());
    assertThat(p.objectDescription()).isEqualTo(snapshot.objectDescription());
    assertThat(p.rawJson()).isEqualTo(snapshot.rawJson());
    assertThat(p.firstSeenAt()).isEqualTo(T0);
    assertThat(p.lastSeenAt()).isEqualTo(T0);
    assertThat(p.closedEmittedAt()).isNull();
  }

  @Test
  void discoverRecordsTheFirstHistoryEntry() {
    var p = discovered();
    assertThat(p.history())
        .containsExactly(new SituationHistoryEntry(null, 1, false, snapshot().pncpUpdatedAt(), T0));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 5})
  void discoverRejectsUnknownSituation(int id) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Procurement.discover(withSituation(id, "?"), at(T0)));
  }

  @Test
  void discoverAcceptsEmptyDescription() {
    var p = Procurement.discover(snapshot(b -> b.objectDescription = null), at(T0)).procurement();
    assertThat(p.objectDescription()).isEmpty();
  }

  @Test
  void historyIsUnmodifiable() {
    var history = discovered().history();
    assertThatThrownBy(history::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- applySnapshot: no change

  @Test
  void sameHashEmitsNothingAndOnlyTouchesLastSeenAt() {
    var p = discovered();
    var events = p.applySnapshot(snapshot(b -> b.rawJson = "{\"changed\":\"raw only\"}"), at(T1));
    assertThat(events).isEmpty();
    assertThat(p.lastSeenAt()).isEqualTo(T1);
    assertThat(p.firstSeenAt()).isEqualTo(T0);
    assertThat(p.revision()).isEqualTo(1);
    assertThat(p.history()).hasSize(1);
    assertThat(p.rawJson()).isEqualTo(snapshot().rawJson());
  }

  @Test
  void idMismatchIsRejectedAndNothingChanges() {
    var p = discovered();
    var other = snapshot(b -> b.id = new ProcurementId("83021808000182-1-000682/2026"));
    assertThatIllegalArgumentException().isThrownBy(() -> p.applySnapshot(other, at(T1)));
    assertThat(p.lastSeenAt()).isEqualTo(T0);
    assertThat(p.revision()).isEqualTo(1);
  }

  @Test
  void unknownSituationIsRejectedAndNothingChanges() {
    var p = discovered();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> p.applySnapshot(withSituation(9, "?"), at(T1)));
    assertThat(p.lastSeenAt()).isEqualTo(T0);
    assertThat(p.revision()).isEqualTo(1);
    assertThat(p.situation().id()).isEqualTo(1);
  }

  // ---- applySnapshot: update

  @Test
  void changeEmitsOneUpdatedEventWithTheNewSnapshot() {
    var p = discovered();
    var next = snapshot(b -> b.objectDescription = "Novo objeto");
    var events = p.applySnapshot(next, at(T1));
    assertThat(events).hasSize(1);
    var updated = (ProcurementUpdated) events.getFirst();
    assertThat(updated.aggregateId()).isEqualTo(ID.value());
    assertThat(updated.occurredAt()).isEqualTo(T1);
    assertThat(updated.changedFields()).containsExactly("objectDescription");
    assertThat(updated.snapshot().objectDescription()).isEqualTo("Novo objeto");
    assertThat(updated.snapshot().revision()).isEqualTo(2);
    assertThat(p.objectDescription()).isEqualTo("Novo objeto");
    assertThat(p.contentHash()).isEqualTo(ContentHash.of(next));
    assertThat(p.revision()).isEqualTo(2);
    assertThat(p.lastSeenAt()).isEqualTo(T1);
    assertThat(p.history()).hasSize(1);
  }

  @Test
  void changeReplacesEveryStoredField() {
    var p = discovered();
    var next =
        snapshot(
            b -> {
              b.organ = new Organ("83021808000183", "Outro orgao");
              b.unit =
                  new OrganUnit("2", "Outra unidade", Uf.PR, "Curitiba", new IbgeCode("4106902"));
              b.modality = new Modality(8, "Dispensa");
              b.disputeMode = new DisputeMode(2, "Fechado");
              b.legalBasis = new LegalBasis(2, "Outra lei");
              b.srp = true;
              b.estimatedValue = Money.of("10.00");
              b.awardedValue = null;
              b.proposalWindow = null;
              b.pncpUpdatedAt = T0;
              b.rawJson = "{\"v\":2}";
            });
    p.applySnapshot(next, at(T1));
    assertThat(p.organ()).isEqualTo(next.organ());
    assertThat(p.unit()).isEqualTo(next.unit());
    assertThat(p.modality()).isEqualTo(next.modality());
    assertThat(p.disputeMode()).isEqualTo(next.disputeMode());
    assertThat(p.legalBasis()).isEqualTo(next.legalBasis());
    assertThat(p.srp()).isTrue();
    assertThat(p.estimatedValue()).isEqualTo(next.estimatedValue());
    assertThat(p.awardedValue()).isNull();
    assertThat(p.proposalWindow()).isNull();
    assertThat(p.pncpUpdatedAt()).isEqualTo(T0);
    assertThat(p.rawJson()).isEqualTo("{\"v\":2}");
  }

  static Stream<Arguments> singleFieldChanges() {
    Consumer<Fixtures.Builder> situationName =
        b -> b.situation = new PncpSituation(1, "Outro nome");
    Consumer<Fixtures.Builder> organ =
        b -> b.organ = new Organ("83021808000183", "Prefeitura de Exemplo");
    Consumer<Fixtures.Builder> unit =
        b -> b.unit = new OrganUnit("9", "Secretaria de Compras", null, null, null);
    Consumer<Fixtures.Builder> modality = b -> b.modality = new Modality(7, "Concorrencia");
    Consumer<Fixtures.Builder> dispute = b -> b.disputeMode = null;
    Consumer<Fixtures.Builder> legal = b -> b.legalBasis = null;
    Consumer<Fixtures.Builder> srp = b -> b.srp = true;
    Consumer<Fixtures.Builder> estimated = b -> b.estimatedValue = Money.of("1.00");
    Consumer<Fixtures.Builder> awarded = b -> b.awardedValue = null;
    Consumer<Fixtures.Builder> window = b -> b.proposalWindow = null;
    Consumer<Fixtures.Builder> updatedAt = b -> b.pncpUpdatedAt = b.pncpUpdatedAt.plusSeconds(5);
    Consumer<Fixtures.Builder> description = b -> b.objectDescription = "outro";
    return Stream.of(
        Arguments.of("situation", situationName),
        Arguments.of("organ", organ),
        Arguments.of("unit", unit),
        Arguments.of("modality", modality),
        Arguments.of("disputeMode", dispute),
        Arguments.of("legalBasis", legal),
        Arguments.of("srp", srp),
        Arguments.of("estimatedValue", estimated),
        Arguments.of("awardedValue", awarded),
        Arguments.of("proposalWindow", window),
        Arguments.of("pncpUpdatedAt", updatedAt),
        Arguments.of("objectDescription", description));
  }

  @ParameterizedTest
  @MethodSource("singleFieldChanges")
  void changedFieldsNamesTheDifferingField(String field, Consumer<Fixtures.Builder> change) {
    var p = discovered();
    var events = p.applySnapshot(snapshot(change), at(T1));
    assertThat(events).hasSize(1);
    assertThat(((ProcurementUpdated) events.getFirst()).changedFields()).containsExactly(field);
  }

  @Test
  void changedFieldsAreSorted() {
    var p = discovered();
    var events =
        p.applySnapshot(
            snapshot(
                b -> {
                  b.srp = true;
                  b.objectDescription = "z";
                  b.awardedValue = null;
                  b.modality = new Modality(1, "m");
                }),
            at(T1));
    assertThat(((ProcurementUpdated) events.getFirst()).changedFields())
        .containsExactly("awardedValue", "modality", "objectDescription", "srp");
  }

  @Test
  void changedFieldsIsImmutable() {
    var p = discovered();
    var updated =
        (ProcurementUpdated) p.applySnapshot(snapshot(b -> b.srp = true), at(T1)).getFirst();
    assertThatThrownBy(() -> updated.changedFields().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
    var source = new java.util.ArrayList<>(List.of("a"));
    var event = new ProcurementUpdated("id", T0, updated.snapshot(), source);
    source.add("b");
    assertThat(event.changedFields()).containsExactly("a");
  }

  @Test
  void laterPublishedAtAloneIsNotAChange() {
    var p = discovered();
    var later = snapshot().publishedAt().plus(Duration.ofDays(3));
    var events = p.applySnapshot(snapshot(b -> b.publishedAt = later), at(T1));
    assertThat(events).isEmpty();
    assertThat(p.publishedAt()).isEqualTo(snapshot().publishedAt());
    assertThat(p.contentHash()).isEqualTo(ContentHash.of(snapshot()));
    assertThat(p.revision()).isEqualTo(1);
    assertThat(p.lastSeenAt()).isEqualTo(T1);
  }

  @Test
  void publishedAtStaysFrozenWhenOtherFieldsChange() {
    var p = discovered();
    var later = snapshot().publishedAt().plus(Duration.ofDays(3));
    var events =
        p.applySnapshot(
            snapshot(
                b -> {
                  b.publishedAt = later;
                  b.srp = true;
                }),
            at(T1));
    var updated = (ProcurementUpdated) events.getFirst();
    assertThat(updated.changedFields()).containsExactly("srp");
    assertThat(updated.snapshot().publishedAt()).isEqualTo(snapshot().publishedAt());
    assertThat(p.publishedAt()).isEqualTo(snapshot().publishedAt());
    assertThat(p.contentHash()).isEqualTo(ContentHash.of(snapshot(b -> b.srp = true)));
  }

  // ---- applySnapshot: stale snapshots

  @Test
  void olderSnapshotIsIgnoredButStillSeen() {
    var p = discovered();
    p.applySnapshot(
        snapshot(
            b -> {
              b.pncpUpdatedAt = T0;
              b.objectDescription = "novo";
            }),
        at(T1));
    var hash = p.contentHash();
    var stale =
        snapshot(
            b -> {
              b.pncpUpdatedAt = T0.minusSeconds(1);
              b.objectDescription = "antigo";
              b.situation = new PncpSituation(2, "Revogada");
            });
    var events = p.applySnapshot(stale, at(T2));
    assertThat(events).isEmpty();
    assertThat(p.lastSeenAt()).isEqualTo(T2);
    assertThat(p.contentHash()).isEqualTo(hash);
    assertThat(p.objectDescription()).isEqualTo("novo");
    assertThat(p.pncpUpdatedAt()).isEqualTo(T0);
    assertThat(p.situation().id()).isEqualTo(1);
    assertThat(p.status()).isEqualTo(new Published());
    assertThat(p.revision()).isEqualTo(2);
    assertThat(p.history()).hasSize(1);
  }

  @Test
  void equalTimestampWithDifferentContentIsApplied() {
    var p = discovered();
    var events = p.applySnapshot(snapshot(b -> b.objectDescription = "mesmo instante"), at(T1));
    assertThat(events).hasSize(1);
    assertThat(p.objectDescription()).isEqualTo("mesmo instante");
    assertThat(p.pncpUpdatedAt()).isEqualTo(snapshot().pncpUpdatedAt());
  }

  @Test
  void staleSnapshotWithUnknownSituationIsStillRejected() {
    var p = discovered();
    var stale =
        snapshot(
            b -> {
              b.pncpUpdatedAt = T0.minusSeconds(86_400);
              b.situation = new PncpSituation(9, "?");
            });
    assertThatIllegalArgumentException().isThrownBy(() -> p.applySnapshot(stale, at(T1)));
    assertThat(p.lastSeenAt()).isEqualTo(T0);
  }

  // ---- applySnapshot: situation transitions

  static Stream<Arguments> transitions() {
    // from id, to id, expected status name, unexpected
    return Stream.of(
        Arguments.of(1, 4, "Suspended", false),
        Arguments.of(4, 1, "Published", false),
        Arguments.of(1, 2, "Revoked", false),
        Arguments.of(1, 3, "Annulled", false),
        Arguments.of(4, 2, "Revoked", false),
        Arguments.of(4, 3, "Annulled", false),
        Arguments.of(2, 1, "Published", true),
        Arguments.of(2, 4, "Suspended", true),
        Arguments.of(2, 3, "Annulled", true),
        Arguments.of(3, 1, "Published", true),
        Arguments.of(3, 4, "Suspended", true),
        Arguments.of(3, 2, "Revoked", true));
  }

  @ParameterizedTest
  @MethodSource("transitions")
  void situationChangeEmitsUpdatedThenSituationChanged(
      int from, int to, String status, boolean unexpected) {
    var p = Procurement.discover(withSituation(from, "from"), at(T0)).procurement();
    var updatedAt = T0.plusSeconds(1);
    var events =
        p.applySnapshot(
            snapshot(
                b -> {
                  b.situation = new PncpSituation(to, "to");
                  b.pncpUpdatedAt = updatedAt;
                }),
            at(T1));

    assertThat(events).hasSize(2);
    assertThat(events.get(0)).isInstanceOf(ProcurementUpdated.class);
    var changed = (ProcurementSituationChanged) events.get(1);
    assertThat(changed.aggregateId()).isEqualTo(ID.value());
    assertThat(changed.occurredAt()).isEqualTo(T1);
    assertThat(changed.from()).isEqualTo(new PncpSituation(from, "from"));
    assertThat(changed.to()).isEqualTo(new PncpSituation(to, "to"));
    assertThat(changed.pncpUpdatedAt()).isEqualTo(updatedAt);
    assertThat(changed.unexpected()).isEqualTo(unexpected);
    assertThat(((ProcurementUpdated) events.get(0)).snapshot().status())
        .isEqualTo(status.toUpperCase(Locale.ROOT));
    assertThat(p.status().name()).isEqualTo(status);
    assertThat(p.situation()).isEqualTo(new PncpSituation(to, "to"));
    assertThat(p.revision()).isEqualTo(2);
    assertThat(p.history()).hasSize(2);
    assertThat(p.history().getLast())
        .isEqualTo(new SituationHistoryEntry(from, to, unexpected, updatedAt, T1));
  }

  @Test
  void sameSituationIdWithOtherFieldsDoesNotRecordHistory() {
    var p = discovered();
    p.applySnapshot(snapshot(b -> b.srp = true), at(T1));
    assertThat(p.history()).hasSize(1);
  }

  @Test
  void restoredFromTerminalStateIsStillUnexpected() {
    var p = Procurement.discover(withSituation(2, "Revogada"), at(T0)).procurement();
    assertThat(p.status()).isEqualTo(new Revoked());
    var events = p.applySnapshot(withSituation(1, "Divulgada"), at(T1));
    assertThat(((ProcurementSituationChanged) events.get(1)).unexpected()).isTrue();
    assertThat(p.status()).isEqualTo(new Published());
  }

  @Test
  void statusFollowsAnnulment() {
    var p = discovered();
    p.applySnapshot(withSituation(3, "Anulada"), at(T1));
    assertThat(p.status()).isEqualTo(new Annulled());
    p.applySnapshot(withSituation(4, "Suspensa"), at(T2));
    assertThat(p.status()).isEqualTo(new Suspended());
  }

  // ---- closeIfExpired

  private static Procurement withWindow(ProposalWindow window, int situationId) {
    return Procurement.discover(
            snapshot(
                b -> {
                  b.proposalWindow = window;
                  b.situation = new PncpSituation(situationId, "s");
                }),
            at(T0))
        .procurement();
  }

  @Test
  void closeIfExpiredEmitsOnce() {
    var p = withWindow(new ProposalWindow(T0.minusSeconds(100), T1), 1);
    var first = p.closeIfExpired(at(T2));
    assertThat(first).isPresent();
    var closed = (ProcurementClosed) first.get();
    assertThat(closed.aggregateId()).isEqualTo(ID.value());
    assertThat(closed.occurredAt()).isEqualTo(T2);
    assertThat(closed.closingAt()).isEqualTo(T1);
    assertThat(closed.eventType()).isEqualTo("ProcurementClosed");
    assertThat(p.closedEmittedAt()).isEqualTo(T2);
    assertThat(p.revision()).isEqualTo(2);

    assertThat(p.closeIfExpired(at(T2.plusSeconds(1)))).isEmpty();
    assertThat(p.closeIfExpired(at(T2.plusSeconds(2)))).isEmpty();
    assertThat(p.revision()).isEqualTo(2);
    assertThat(p.closedEmittedAt()).isEqualTo(T2);
  }

  @Test
  void closeIfExpiredDoesNotTouchLastSeenAt() {
    var p = withWindow(new ProposalWindow(null, T1), 1);
    p.closeIfExpired(at(T2));
    assertThat(p.lastSeenAt()).isEqualTo(T0);
  }

  @Test
  void closingAtEqualToNowIsNotExpiredYet() {
    var p = withWindow(new ProposalWindow(null, T1), 1);
    assertThat(p.closeIfExpired(at(T1))).isEmpty();
    assertThat(p.closeIfExpired(at(T1.plusNanos(1)))).isPresent();
  }

  @Test
  void closeIfExpiredIgnoresMissingWindowOrClosing() {
    assertThat(withWindow(null, 1).closeIfExpired(at(T2))).isEmpty();
    assertThat(withWindow(new ProposalWindow(T0, null), 1).closeIfExpired(at(T2))).isEmpty();
    assertThat(withWindow(new ProposalWindow(null, null), 1).closeIfExpired(at(T2))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4})
  void closeIfExpiredOnlyAppliesToPublished(int situationId) {
    var p = withWindow(new ProposalWindow(null, T1), situationId);
    assertThat(p.closeIfExpired(at(T2))).isEmpty();
    assertThat(p.closedEmittedAt()).isNull();
    assertThat(p.revision()).isEqualTo(1);
  }

  // ---- proposalPhase

  @Test
  void proposalPhaseFollowsTheWindow() {
    var p = withWindow(new ProposalWindow(T1, T2), 1);
    assertThat(p.proposalPhase(T1.minusSeconds(1))).isEqualTo(ProposalPhase.UPCOMING);
    assertThat(p.proposalPhase(T1)).isEqualTo(ProposalPhase.OPEN);
    assertThat(p.proposalPhase(T2.minusSeconds(1))).isEqualTo(ProposalPhase.OPEN);
    assertThat(p.proposalPhase(T2)).isEqualTo(ProposalPhase.CLOSED);
    assertThat(p.proposalPhase(T2.plusSeconds(1))).isEqualTo(ProposalPhase.CLOSED);
  }

  @Test
  void proposalPhaseIsUnknownWithoutWindow() {
    assertThat(withWindow(null, 1).proposalPhase(T0)).isEqualTo(ProposalPhase.UNKNOWN);
    assertThat(withWindow(new ProposalWindow(null, null), 1).proposalPhase(T0))
        .isEqualTo(ProposalPhase.UNKNOWN);
  }

  // ---- restore

  private static PncpSnapshot currentOf(Procurement p) {
    return new PncpSnapshot(
        p.id(),
        p.situation(),
        p.organ(),
        p.unit(),
        p.modality(),
        p.disputeMode(),
        p.legalBasis(),
        p.srp(),
        p.estimatedValue(),
        p.awardedValue(),
        p.proposalWindow(),
        p.publishedAt(),
        p.pncpUpdatedAt(),
        p.objectDescription(),
        p.rawJson());
  }

  private static Procurement restoreCopyOf(Procurement p, long version) {
    return Procurement.restore(
        currentOf(p),
        p.contentHash(),
        version,
        p.history(),
        p.firstSeenAt(),
        p.lastSeenAt(),
        p.closedEmittedAt(),
        p.revision());
  }

  @Test
  void restoreRebuildsTheSameState() {
    var p = discovered();
    p.applySnapshot(withSituation(4, "Suspensa"), at(T1));
    var r = restoreCopyOf(p, 7);
    assertThat(r.id()).isEqualTo(p.id());
    assertThat(r.contentHash()).isEqualTo(p.contentHash());
    assertThat(r.version()).isEqualTo(7);
    assertThat(r.status()).isEqualTo(new Suspended());
    assertThat(r.situation()).isEqualTo(p.situation());
    assertThat(r.organ()).isEqualTo(p.organ());
    assertThat(r.unit()).isEqualTo(p.unit());
    assertThat(r.modality()).isEqualTo(p.modality());
    assertThat(r.disputeMode()).isEqualTo(p.disputeMode());
    assertThat(r.legalBasis()).isEqualTo(p.legalBasis());
    assertThat(r.srp()).isEqualTo(p.srp());
    assertThat(r.estimatedValue()).isEqualTo(p.estimatedValue());
    assertThat(r.awardedValue()).isEqualTo(p.awardedValue());
    assertThat(r.proposalWindow()).isEqualTo(p.proposalWindow());
    assertThat(r.publishedAt()).isEqualTo(p.publishedAt());
    assertThat(r.pncpUpdatedAt()).isEqualTo(p.pncpUpdatedAt());
    assertThat(r.objectDescription()).isEqualTo(p.objectDescription());
    assertThat(r.rawJson()).isEqualTo(p.rawJson());
    assertThat(r.history()).isEqualTo(p.history());
    assertThat(r.firstSeenAt()).isEqualTo(p.firstSeenAt());
    assertThat(r.lastSeenAt()).isEqualTo(p.lastSeenAt());
    assertThat(r.closedEmittedAt()).isNull();
    assertThat(r.revision()).isEqualTo(p.revision());
  }

  @Test
  void restoreKeepsTheStoredHashAsGiven() {
    var p = discovered();
    var stored = new ContentHash(new byte[] {1, 2, 3});
    var r = Procurement.restore(currentOf(p), stored, 0, p.history(), T0, T0, null, 1);
    assertThat(r.contentHash()).isEqualTo(stored);
  }

  @Test
  void restoreKeepsClosedEmittedAtSoClosingStaysOnce() {
    var p = withWindow(new ProposalWindow(null, T1), 1);
    p.closeIfExpired(at(T2));
    var r = restoreCopyOf(p, 3);
    assertThat(r.closedEmittedAt()).isEqualTo(T2);
    assertThat(r.closeIfExpired(at(T2.plusSeconds(5)))).isEmpty();
  }

  @Test
  void domainMethodsNeverChangeTheOptimisticLockVersion() {
    var r = restoreCopyOf(withWindow(new ProposalWindow(null, T1), 1), 7);
    var tweaked =
        snapshot(
            b -> {
              b.srp = true;
              b.proposalWindow = new ProposalWindow(null, T1);
            });
    r.applySnapshot(tweaked, at(T1));
    r.closeIfExpired(at(T2));
    assertThat(r.version()).isEqualTo(7);
    assertThat(r.revision()).isEqualTo(3);
  }

  @Test
  void restoreCopiesTheHistory() {
    var p = discovered();
    var source = new java.util.ArrayList<>(p.history());
    var r = Procurement.restore(currentOf(p), p.contentHash(), 0, source, T0, T0, null, 1);
    source.clear();
    assertThat(r.history()).hasSize(1);
  }

  @Test
  void restoreRequiresItsInputs() {
    var p = discovered();
    var current = currentOf(p);
    var hash = p.contentHash();
    var history = p.history();
    assertThatNullPointerException()
        .isThrownBy(() -> Procurement.restore(null, hash, 0, history, T0, T0, null, 1));
    assertThatNullPointerException()
        .isThrownBy(() -> Procurement.restore(current, null, 0, history, T0, T0, null, 1));
    assertThatNullPointerException()
        .isThrownBy(() -> Procurement.restore(current, hash, 0, null, T0, T0, null, 1));
    assertThatNullPointerException()
        .isThrownBy(() -> Procurement.restore(current, hash, 0, history, null, T0, null, 1));
    assertThatNullPointerException()
        .isThrownBy(() -> Procurement.restore(current, hash, 0, history, T0, null, null, 1));
  }

  @Test
  void restoreRejectsAnUnknownSituation() {
    var p = discovered();
    var unknown = withSituation(9, "?");
    var hash = p.contentHash();
    var history = p.history();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Procurement.restore(unknown, hash, 0, history, T0, T0, null, 1));
  }
}
