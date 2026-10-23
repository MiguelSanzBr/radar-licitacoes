package br.dev.miguelsanzbr.radar.alerts.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Delivery;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class QueueDigestAlertUseCaseTest {

  private static final AlertMatched ALERT =
      AlertMatched.of(
          new SearchMatch(UUID.randomUUID(), UUID.randomUUID(), Frequency.DAILY),
          Facts.procurement().build());

  private final List<String> calls = new ArrayList<>();
  private final Set<UUID> seenEvents = new HashSet<>();
  private int open;

  private final DeliveryLog log =
      new DeliveryLog() {
        @Override
        public Optional<Delivery> find(UUID savedSearchId, String procurementId) {
          return Optional.empty();
        }

        @Override
        public void markSent(UUID savedSearchId, String procurementId, Instant at) {}

        @Override
        public void keepForDigest(AlertMatched alert) {
          calls.add("keep " + alert.procurementId() + " in tx=" + (open > 0));
        }

        @Override
        public List<Queued> unsentDigest() {
          return List.of();
        }
      };

  private final QueueDigestAlertUseCase useCase =
      new QueueDigestAlertUseCase(
          log,
          (consumer, eventId, at) -> seenEvents.add(eventId),
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
          },
          Clock.fixed(Facts.NOW, ZoneOffset.UTC));

  @Test
  void theFirstDeliveryOfAnEventStoresWhatTheDigestShowsInsideATransaction() {
    useCase.queue(UUID.randomUUID(), ALERT);

    assertThat(calls).containsExactly("keep " + Facts.ID + " in tx=true");
  }

  @Test
  void aRedeliveredEventStoresNothingTwice() {
    var event = UUID.randomUUID();

    useCase.queue(event, ALERT);
    useCase.queue(event, ALERT);

    assertThat(calls).hasSize(1);
  }
}
