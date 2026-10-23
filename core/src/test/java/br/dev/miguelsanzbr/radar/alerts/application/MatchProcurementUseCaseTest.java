package br.dev.miguelsanzbr.radar.alerts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.port.MatcherMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MatchProcurementUseCaseTest {

  private static final Clock CLOCK = Clock.fixed(Facts.NOW, ZoneOffset.UTC);

  private final List<SearchMatch> found = new ArrayList<>();
  private final Set<UUID> seenEvents = new HashSet<>();
  private final Set<String> deliveries = new HashSet<>();
  private final List<AlertMatched> emitted = new ArrayList<>();
  private final List<Instant> emittedAt = new ArrayList<>();
  private final List<String> calls = new ArrayList<>();
  private final List<Integer> counted = new ArrayList<>();
  private int open;
  private boolean emitFails;

  private final UnitOfWork unitOfWork =
      new UnitOfWork() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
          open++;
          try {
            return work.get();
          } finally {
            open--;
          }
        }
      };

  private final MatchProcurementUseCase useCase =
      new MatchProcurementUseCase(
          (facts, now) -> {
            calls.add("search in tx=" + (open > 0));
            return found;
          },
          (searchId, procurementId, at) -> deliveries.add(searchId + "|" + procurementId),
          (consumer, eventId, at) -> {
            calls.add("inbox " + consumer + " in tx=" + (open > 0));
            return seenEvents.add(eventId);
          },
          (event, at) -> {
            if (emitFails) {
              throw new IllegalStateException("outbox down");
            }
            calls.add("emit in tx=" + (open > 0));
            emitted.add(event);
            emittedAt.add(at);
          },
          new MatcherMetrics() {
            @Override
            public <T> T timed(Supplier<T> work) {
              calls.add("timed");
              return work.get();
            }

            @Override
            public void matched(int count) {
              counted.add(count);
            }
          },
          unitOfWork,
          CLOCK);

  private static SearchMatch match(Frequency frequency) {
    return new SearchMatch(UUID.randomUUID(), UUID.randomUUID(), frequency);
  }

  @Test
  void aFirstDiscoveryEmitsOneAlertPerMatchingSearchInsideOneTransaction() {
    found.addAll(List.of(match(Frequency.INSTANT), match(Frequency.DAILY)));

    var alerts = useCase.onDiscovered(UUID.randomUUID(), Facts.procurement().build());

    assertThat(alerts).isEqualTo(2);
    assertThat(emitted)
        .extracting(AlertMatched::frequency)
        .containsExactly(Frequency.INSTANT, Frequency.DAILY);
    assertThat(emitted).extracting(AlertMatched::procurementId).containsOnly(Facts.ID);
    assertThat(emittedAt).containsOnly(Facts.NOW);
    assertThat(counted).containsExactly(2);
    assertThat(calls)
        .containsExactly(
            "timed",
            "inbox alerts-matcher in tx=true",
            "search in tx=true",
            "emit in tx=true",
            "emit in tx=true");
  }

  @Test
  void aRepeatedEventIsSkippedBeforeAnySearch() {
    var eventId = UUID.randomUUID();
    found.add(match(Frequency.INSTANT));
    useCase.onDiscovered(eventId, Facts.procurement().build());
    calls.clear();

    var again = useCase.onDiscovered(eventId, Facts.procurement().build());

    assertThat(again).isZero();
    assertThat(emitted).hasSize(1);
    assertThat(calls).doesNotContain("search in tx=true");
    assertThat(counted).containsExactly(1, 0);
  }

  @Test
  void aSearchThatAlreadyHasADeliveryForTheProcurementEmitsNothing() {
    found.add(match(Frequency.INSTANT));

    useCase.onDiscovered(UUID.randomUUID(), Facts.procurement().build());
    var second = useCase.onDiscovered(UUID.randomUUID(), Facts.procurement().build());

    assertThat(second).isZero();
    assertThat(emitted).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "objectDescription",
        "estimatedValue",
        "proposalWindow",
        "unit",
        "organ",
        "modality"
      })
  void anUpdateThatTouchesWhatSearchesLookAtIsMatched(String field) {
    found.add(match(Frequency.INSTANT));

    var alerts =
        useCase.onUpdated(
            UUID.randomUUID(), Facts.procurement().build(), List.of("situation", field));

    assertThat(alerts).isEqualTo(1);
  }

  @Test
  void anUpdateOfOtherFieldsTouchesNothing() {
    found.add(match(Frequency.INSTANT));

    var alerts =
        useCase.onUpdated(
            UUID.randomUUID(),
            Facts.procurement().build(),
            List.of("situation", "pncpUpdatedAt", "srp"));

    assertThat(alerts).isZero();
    assertThat(calls).isEmpty();
    assertThat(counted).isEmpty();
  }

  @Test
  void aFailureWhileEmittingPropagatesAndCountsNothing() {
    found.add(match(Frequency.INSTANT));
    emitFails = true;
    var facts = Facts.procurement().build();
    var eventId = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.onDiscovered(eventId, facts))
        .isInstanceOf(IllegalStateException.class);

    assertThat(counted).isEmpty();
  }

  @Test
  void aProcurementWithoutMatchingSearchesEmitsNothing() {
    assertThat(useCase.onDiscovered(UUID.randomUUID(), Facts.procurement().build())).isZero();
    assertThat(emitted).isEmpty();
    assertThat(counted).containsExactly(0);
  }
}
