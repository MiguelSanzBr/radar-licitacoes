package br.dev.miguelsanzbr.radar.alerts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.RecordingNotifierMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Delivery;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.time.Clock;
import java.time.Duration;
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

class SendInstantAlertUseCaseTest {

  private static final Instant NOW = Facts.NOW.plusSeconds(5);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final AlertMatched ALERT =
      AlertMatched.of(
          new SearchMatch(UUID.randomUUID(), UUID.randomUUID(), Frequency.INSTANT),
          Facts.procurement().build());

  private final List<String> calls = new ArrayList<>();
  private final Set<UUID> seenEvents = new HashSet<>();
  private final RecordingNotifierMetrics metrics = new RecordingNotifierMetrics();
  private Delivery delivery = new Delivery(Facts.NOW, null, "fulano@example.com", "Notebooks");
  private boolean smtpDown;
  private int open;

  private final DeliveryLog log =
      new DeliveryLog() {
        @Override
        public Optional<Delivery> find(UUID savedSearchId, String procurementId) {
          assertThat(savedSearchId).isEqualTo(ALERT.savedSearchId());
          assertThat(procurementId).isEqualTo(ALERT.procurementId());
          return Optional.ofNullable(delivery);
        }

        @Override
        public void markSent(UUID savedSearchId, String procurementId, Instant at) {
          calls.add("mark in tx=" + (open > 0) + " at=" + at);
        }

        @Override
        public void keepForDigest(AlertMatched alert) {}

        @Override
        public List<Queued> unsentDigest() {
          return List.of();
        }
      };

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

  private final SendInstantAlertUseCase useCase =
      new SendInstantAlertUseCase(
          log,
          (consumer, eventId, at) -> {
            calls.add("inbox " + consumer + " in tx=" + (open > 0));
            return seenEvents.add(eventId);
          },
          new AlertMailer() {
            @Override
            public void sendInstant(String to, String searchName, AlertMatched alert) {
              if (smtpDown) {
                throw new MailDeliveryException(new IllegalStateException("smtp down"));
              }
              calls.add("send " + to + " " + searchName + " in tx=" + (open > 0));
            }

            @Override
            public void sendDigest(String to, List<Queued> items) {}
          },
          metrics,
          unitOfWork,
          CLOCK);

  @Test
  void sendsFirstThenMarksSentInAShortTransactionAndTimesTheLatency() {
    var event = UUID.randomUUID();

    assertThat(useCase.send(event, ALERT)).isTrue();

    assertThat(calls)
        .containsExactly(
            "send fulano@example.com Notebooks in tx=false",
            "mark in tx=true at=" + NOW,
            "inbox alerts-notifier in tx=true");
    assertThat(metrics.latencies).containsExactly(Duration.ofSeconds(5));
  }

  @Test
  void aDeliveryAlreadySentIsSkippedSoARedeliveredEventNeverSendsTwice() {
    delivery = new Delivery(Facts.NOW, Facts.NOW.plusSeconds(1), "fulano@example.com", "Notebooks");

    assertThat(useCase.send(UUID.randomUUID(), ALERT)).isFalse();

    assertThat(calls).isEmpty();
    assertThat(metrics.latencies).isEmpty();
  }

  @Test
  void aDeliveryThatNoLongerExistsOrBelongsToAnActiveSubscriberIsSkipped() {
    delivery = null;

    assertThat(useCase.send(UUID.randomUUID(), ALERT)).isFalse();

    assertThat(calls).isEmpty();
  }

  @Test
  void whenTheServerRefusesNothingIsMarkedAndTheFailureReachesTheListener() {
    smtpDown = true;

    assertThatThrownBy(() -> useCase.send(UUID.randomUUID(), ALERT))
        .isInstanceOf(MailDeliveryException.class);

    assertThat(calls).isEmpty();
    assertThat(metrics.latencies).isEmpty();
  }
}
