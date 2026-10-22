package br.dev.miguelsanzbr.radar.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.InMemoryProcurements;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.RecordingOutbox;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementClosed;
import br.dev.miguelsanzbr.radar.ingest.domain.ProposalWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class CloseExpiredUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final Instant PAST = Instant.parse("2026-10-22T11:00:00Z");
  private static final Instant FUTURE = Instant.parse("2026-10-22T13:00:00Z");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final InMemoryProcurements procurements = new InMemoryProcurements();
  private final RecordingOutbox outbox = new RecordingOutbox();
  private final AtomicInteger transactions = new AtomicInteger();
  private final TransactionOperations counting =
      new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          transactions.incrementAndGet();
          return action.doInTransaction(null);
        }
      };
  private final CloseExpiredUseCase useCase =
      new CloseExpiredUseCase(procurements, outbox, counting, clock, 2);

  private Procurement procurement(int n, Instant closingAt) {
    var s = BackfillUseCaseTest.snapshot(n);
    var withWindow =
        new PncpSnapshot(
            s.id(),
            s.situation(),
            s.organ(),
            s.unit(),
            s.modality(),
            null,
            null,
            false,
            null,
            null,
            new ProposalWindow(null, closingAt),
            s.publishedAt(),
            s.pncpUpdatedAt(),
            s.objectDescription(),
            s.rawJson());
    return Procurement.discover(withWindow, clock).procurement();
  }

  private void store(int n, Instant closingAt) {
    procurements.save(procurement(n, closingAt));
  }

  @Test
  void closesEveryExpiredNoticeInPagesOfTheConfiguredSizeOneTransactionEach() {
    store(1, PAST);
    store(2, PAST);
    store(3, PAST);
    store(4, FUTURE);

    var closed = useCase.closeExpired();

    assertThat(closed).isEqualTo(3);
    assertThat(transactions).hasValue(2);
    assertThat(outbox.appended).hasSize(3);
    assertThat(outbox.appended)
        .allSatisfy(
            a -> {
              assertThat(a.type()).isEqualTo("Procurement");
              assertThat(a.version()).isEqualTo(2);
              assertThat(a.events()).hasSize(1).allMatch(e -> e instanceof ProcurementClosed);
            });
  }

  @Test
  void runningAgainClosesNothingMore() {
    store(1, PAST);
    useCase.closeExpired();
    var events = outbox.eventCount();

    assertThat(useCase.closeExpired()).isZero();
    assertThat(useCase.closeExpired()).isZero();
    assertThat(outbox.eventCount()).isEqualTo(events);
  }

  @Test
  void nothingToCloseOpensNoTransaction() {
    store(1, FUTURE);

    assertThat(useCase.closeExpired()).isZero();
    assertThat(transactions).hasValue(0);
  }

  @Test
  void aCandidateThatDoesNotCloseEndsTheLoopInsteadOfSpinning() {
    var notYet = procurement(1, FUTURE);
    var stubborn =
        new InMemoryProcurements() {
          @Override
          public List<Procurement> findClosingCandidates(Instant now, int limit) {
            return List.of(notYet);
          }
        };
    var stuck = new CloseExpiredUseCase(stubborn, outbox, counting, clock, 2);

    assertThat(stuck.closeExpired()).isZero();
    assertThat(transactions).hasValue(1);
    assertThat(outbox.appended).isEmpty();
  }

  @Test
  void aFailureNamesTheNoticeThatFailed() {
    store(1, PAST);
    var cause = new IllegalStateException("outbox down");
    var broken =
        new CloseExpiredUseCase(
            procurements,
            (type, version, events) -> {
              throw cause;
            },
            counting,
            clock,
            2);

    assertThatThrownBy(broken::closeExpired)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BackfillUseCaseTest.id(1).value())
        .hasCause(cause);
  }
}
