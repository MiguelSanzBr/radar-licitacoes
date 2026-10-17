package br.dev.miguelsanzbr.radar.ingest.domain;

import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class ProcurementPropertiesTest {

  /** Advances one second per reading, so recorded instants are monotonic. */
  private static final class TickingClock extends Clock {
    private Instant now = T0;

    @Override
    public Instant instant() {
      now = now.plusSeconds(1);
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @Provide
  Arbitrary<PncpSnapshot> snapshots() {
    var situation = Arbitraries.integers().between(1, 4).map(id -> new PncpSituation(id, "s" + id));
    var text = Arbitraries.strings().ofMaxLength(20);
    var money = Arbitraries.longs().between(0, 1_000_000L).map(Money::of);
    var published = Arbitraries.longs().between(0, 1_000_000L).map(T0::plusSeconds);
    // a narrow range makes stale, equal and newer timestamps all frequent
    var updated = Arbitraries.longs().between(0, 6).map(T0::plusSeconds);
    return Combinators.combine(
            situation, text, money, published, updated, Arbitraries.of(true, false))
        .as(
            (sit, desc, value, pub, upd, srp) ->
                Fixtures.snapshot(
                    b -> {
                      b.situation = sit;
                      b.objectDescription = desc;
                      b.estimatedValue = value;
                      b.publishedAt = pub;
                      b.pncpUpdatedAt = upd;
                      b.srp = srp;
                    }));
  }

  @Provide
  Arbitrary<List<PncpSnapshot>> sequences() {
    return snapshots().list().ofMinSize(1).ofMaxSize(8);
  }

  /**
   * Independent statement of the transition table: only Published (1) and Suspended (4) can move.
   */
  private static boolean expectedTransition(int fromSituationId) {
    return fromSituationId == 1 || fromSituationId == 4;
  }

  @Property
  void foldModelOverAnyOrderOfSnapshots(@ForAll("sequences") List<PncpSnapshot> all) {
    var clock = new TickingClock();
    var p = Procurement.discover(all.getFirst(), clock).procurement();
    var frozenPublishedAt = all.getFirst().publishedAt();
    var model = all.getFirst();
    var expectedRevision = 1L;
    var expectedHistory = 1;
    var lastSize = p.history().size();

    for (var incoming : all.subList(1, all.size())) {
      var events = p.applySnapshot(incoming, clock);
      var stale = incoming.pncpUpdatedAt().isBefore(model.pncpUpdatedAt());
      if (stale) {
        assertThat(events).isEmpty();
      } else {
        var applied = incoming.withPublishedAt(frozenPublishedAt);
        var changed = !ContentHash.of(applied).equals(ContentHash.of(model));
        assertThat(events.isEmpty()).isEqualTo(!changed);
        if (changed) {
          expectedRevision++;
          if (applied.situation().id() != model.situation().id()) {
            expectedHistory++;
            var entry = p.history().getLast();
            assertThat(entry.fromSituationId()).isEqualTo(model.situation().id());
            assertThat(entry.unexpected()).isEqualTo(!expectedTransition(model.situation().id()));
          }
          model = applied;
        }
      }

      assertThat(p.history().size()).isGreaterThanOrEqualTo(lastSize);
      lastSize = p.history().size();
      assertThat(p.history().stream().map(SituationHistoryEntry::recordedAt).toList()).isSorted();
      assertThat(p.revision()).isEqualTo(expectedRevision);
    }

    assertThat(p.history()).hasSize(expectedHistory);
    assertThat(p.contentHash()).isEqualTo(ContentHash.of(model));
    assertThat(p.publishedAt()).isEqualTo(frozenPublishedAt);
  }

  @Property
  void inOrderSequencesEndWithTheHashOfTheLastSnapshot(
      @ForAll("sequences") List<PncpSnapshot> all) {
    var frozenPublishedAt = all.getFirst().publishedAt();
    var ordered =
        all.stream()
            .sorted(Comparator.comparing(PncpSnapshot::pncpUpdatedAt))
            .map(s -> s.withPublishedAt(frozenPublishedAt))
            .toList();
    var clock = new TickingClock();
    var p = Procurement.discover(ordered.getFirst(), clock).procurement();
    var previous = ordered.getFirst();
    var expectedRevision = 1L;

    for (var next : ordered.subList(1, ordered.size())) {
      p.applySnapshot(next, clock);
      if (!ContentHash.of(next).equals(ContentHash.of(previous))) {
        expectedRevision++;
      }
      previous = next;
    }

    assertThat(p.contentHash()).isEqualTo(ContentHash.of(ordered.getLast()));
    assertThat(p.revision()).isEqualTo(expectedRevision);
    assertThat(p.situation()).isEqualTo(ordered.getLast().situation());
  }
}
