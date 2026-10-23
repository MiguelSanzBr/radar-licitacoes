package br.dev.miguelsanzbr.radar.alerts.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertMailer;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SendDigestUseCaseTest {

  private static final Instant NOW = Facts.NOW.plusSeconds(60);
  private static final UUID ANA = UUID.randomUUID();
  private static final UUID BRUNO = UUID.randomUUID();

  private final List<Queued> queue = new ArrayList<>();
  private final List<String> calls = new ArrayList<>();
  private String refusedAddress;
  private int open;

  private static Queued queued(UUID subscriber, String email, int number) {
    var id = "83021808000182-1-00000%d/2026".formatted(number);
    var alert =
        AlertMatched.of(
            new SearchMatch(UUID.randomUUID(), subscriber, Frequency.DAILY),
            Facts.procurement().id(id).build());
    return new Queued(subscriber, email, "Busca", alert);
  }

  private final DeliveryLog log =
      new DeliveryLog() {
        @Override
        public Optional<Delivery> find(UUID savedSearchId, String procurementId) {
          return Optional.empty();
        }

        @Override
        public void markSent(UUID savedSearchId, String procurementId, Instant at) {
          calls.add("mark " + procurementId + " in tx=" + (open > 0) + " at=" + at);
        }

        @Override
        public void keepForDigest(AlertMatched alert) {}

        @Override
        public List<Queued> unsentDigest() {
          return queue;
        }
      };

  private final SendDigestUseCase useCase =
      new SendDigestUseCase(
          log,
          new AlertMailer() {
            @Override
            public void sendInstant(String to, String searchName, AlertMatched alert) {}

            @Override
            public void sendDigest(String to, List<Queued> items) {
              if (to.equals(refusedAddress)) {
                throw new MailDeliveryException(new IllegalStateException("refused"));
              }
              calls.add("digest " + to + " x" + items.size());
            }
          },
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
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void oneMailPerSubscriberThenAllItsRowsMarkedSentInOneTransaction() {
    queue.addAll(
        List.of(
            queued(ANA, "ana@example.com", 1),
            queued(ANA, "ana@example.com", 2),
            queued(ANA, "ana@example.com", 3),
            queued(BRUNO, "bruno@example.com", 4)));

    var result = useCase.run();

    assertThat(calls)
        .containsExactly(
            "digest ana@example.com x3",
            "mark 83021808000182-1-000001/2026 in tx=true at=" + NOW,
            "mark 83021808000182-1-000002/2026 in tx=true at=" + NOW,
            "mark 83021808000182-1-000003/2026 in tx=true at=" + NOW,
            "digest bruno@example.com x1",
            "mark 83021808000182-1-000004/2026 in tx=true at=" + NOW);
    assertThat(result).isEqualTo(new SendDigestUseCase.Result(2, 4, 0));
  }

  @Test
  void anAddressTheServerRefusesStaysQueuedAndDoesNotBlockTheOthers() {
    queue.addAll(List.of(queued(ANA, "ana@example.com", 1), queued(BRUNO, "bruno@example.com", 2)));
    refusedAddress = "ana@example.com";

    var result = useCase.run();

    assertThat(calls)
        .containsExactly(
            "digest bruno@example.com x1",
            "mark 83021808000182-1-000002/2026 in tx=true at=" + NOW);
    assertThat(result).isEqualTo(new SendDigestUseCase.Result(1, 1, 1));
  }

  @Test
  void nothingQueuedSendsNothing() {
    assertThat(useCase.run()).isEqualTo(new SendDigestUseCase.Result(0, 0, 0));
    assertThat(calls).isEmpty();
  }
}
