package br.dev.miguelsanzbr.radar.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpProtocolException;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ingest.domain.Modality;
import br.dev.miguelsanzbr.radar.ingest.domain.Organ;
import br.dev.miguelsanzbr.radar.ingest.domain.OrganUnit;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSituation;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.RunStatus;
import br.dev.miguelsanzbr.radar.shared.DomainEvent;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class BackfillUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-10-22T04:00:00Z");
  private static final Instant UPDATED = Instant.parse("2026-10-20T10:00:00Z");
  private static final Instant PUBLISHED = Instant.parse("2026-10-20T09:00:00Z");
  private static final LocalDate D20 = LocalDate.of(2026, 10, 20);
  private static final LocalDate D21 = LocalDate.of(2026, 10, 21);
  private static final LocalDate D22 = LocalDate.of(2026, 10, 22);

  static ProcurementId id(int n) {
    return new ProcurementId("83021808000182-1-%06d/2026".formatted(n));
  }

  static PncpSnapshot snapshot(int n, int situation, Instant updatedAt) {
    return new PncpSnapshot(
        id(n),
        new PncpSituation(situation, "Situacao " + situation),
        new Organ("83021808000182", "Prefeitura de Exemplo"),
        new OrganUnit("1010", "Compras", Uf.SC, "Exemplo", new IbgeCode("4205407")),
        new Modality(6, "Pregao Eletronico"),
        null,
        null,
        false,
        null,
        null,
        null,
        PUBLISHED,
        updatedAt,
        "objeto " + n,
        "{}");
  }

  static PncpSnapshot snapshot(int n) {
    return snapshot(n, 1, UPDATED);
  }

  static PncpPage page(int number, int total, PncpSnapshot... items) {
    return new PncpPage(List.of(items), number, total, items.length, total - number);
  }

  static final class FakeGateway implements PncpGateway {
    final Map<String, Object> responses = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    Runnable beforeCall = () -> {};

    void stub(LocalDate day, int page, Object response) {
      responses.put(day + "/" + page, response);
    }

    @Override
    public PncpPage publications(LocalDate from, LocalDate to, int modalityId, int page) {
      assertThat(to).isEqualTo(from);
      assertThat(modalityId).isEqualTo(6);
      beforeCall.run();
      calls.add(from + "/" + page);
      var response = responses.get(from + "/" + page);
      return switch (response) {
        case null -> new PncpPage(List.of(), page, 0, 0, 0);
        case RuntimeException e -> throw e;
        case PncpPage p -> p;
        default -> throw new IllegalStateException("bad stub " + response);
      };
    }

    @Override
    public PncpPage updates(LocalDate from, LocalDate to, int modalityId, int page) {
      throw new UnsupportedOperationException();
    }
  }

  static final class InMemoryProcurements implements ProcurementRepository {
    final Map<ProcurementId, Procurement> store = new HashMap<>();

    @Override
    public Optional<Procurement> findById(ProcurementId id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Procurement> findAllById(Collection<ProcurementId> ids) {
      return ids.stream().map(store::get).filter(p -> p != null).toList();
    }

    @Override
    public Procurement save(Procurement procurement) {
      store.put(procurement.id(), procurement);
      return procurement;
    }
  }

  /**
   * Keeps copies, like a database: mutating a run after saving it does not change what is stored.
   */
  /** Keeps and hands out copies, like a database: mutating a run does not change what is stored. */
  static final class InMemoryRuns implements IngestionRunRepository {
    final Map<Long, IngestionRun> store = new HashMap<>();
    Predicate<IngestionRun> failWhen = run -> false;
    long sequence;

    private static IngestionRun copy(IngestionRun run, Long id, long version) {
      return IngestionRun.restore(
          id,
          version,
          run.kind(),
          run.modalityId(),
          run.windowStart(),
          run.windowEnd(),
          run.cursorPage(),
          run.status(),
          run.fetched(),
          run.discovered(),
          run.updated(),
          run.unchanged(),
          run.startedAt(),
          run.finishedAt(),
          run.error());
    }

    @Override
    public IngestionRun save(IngestionRun run) {
      if (failWhen.test(run)) {
        throw new IllegalStateException("cannot save the run");
      }
      var id = run.id() == null ? ++sequence : run.id();
      var stored = store.get(id);
      if (stored != null && stored.version() != run.version()) {
        throw new IllegalStateException("stale run " + id + " version " + run.version());
      }
      var version = stored == null ? 0 : stored.version() + 1;
      store.put(id, copy(run, id, version));
      return copy(run, id, version);
    }

    @Override
    public Optional<IngestionRun> findResumable(
        IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd) {
      return store.values().stream()
          .filter(r -> r.kind() == kind && r.modalityId() == modalityId)
          .filter(r -> r.windowStart().equals(windowStart) && r.windowEnd().equals(windowEnd))
          .filter(r -> r.status() != RunStatus.DONE)
          .findFirst()
          .map(r -> copy(r, r.id(), r.version()));
    }

    @Override
    public boolean existsDone(
        IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd) {
      return store.values().stream()
          .anyMatch(
              r ->
                  r.kind() == kind
                      && r.modalityId() == modalityId
                      && r.windowStart().equals(windowStart)
                      && r.windowEnd().equals(windowEnd)
                      && r.status() == RunStatus.DONE);
    }
  }

  record Appended(String type, long version, List<? extends DomainEvent> events) {}

  static final class RecordingOutbox implements OutboxAppender {
    final List<Appended> appended = new ArrayList<>();

    @Override
    public void append(String type, long version, List<? extends DomainEvent> events) {
      appended.add(new Appended(type, version, events));
    }

    int eventCount() {
      return appended.stream().mapToInt(a -> a.events().size()).sum();
    }
  }

  private final FakeGateway gateway = new FakeGateway();
  private final InMemoryProcurements procurements = new InMemoryProcurements();
  private final InMemoryRuns runs = new InMemoryRuns();
  private final RecordingOutbox outbox = new RecordingOutbox();
  private final BackfillUseCase useCase =
      new BackfillUseCase(
          gateway,
          procurements,
          runs,
          outbox,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aTwoPageDayDiscoversEveryNoticeAndEmitsOneEventEach() {
    gateway.stub(D20, 1, page(1, 2, snapshot(1), snapshot(2), snapshot(3)));
    gateway.stub(D20, 2, page(2, 2, snapshot(4), snapshot(5)));

    var result = useCase.run(D20, D20, 6);

    assertThat(result).hasSize(1);
    var run = result.getFirst();
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.kind()).isEqualTo(IngestionKind.BACKFILL);
    assertThat(run.cursorPage()).isEqualTo(2);
    assertThat(run.fetched()).isEqualTo(5);
    assertThat(run.discovered()).isEqualTo(5);
    assertThat(run.updated()).isZero();
    assertThat(run.unchanged()).isZero();
    assertThat(run.finishedAt()).isEqualTo(NOW);
    assertThat(procurements.store).hasSize(5);
    assertThat(outbox.appended).hasSize(5);
    assertThat(outbox.appended)
        .allSatisfy(
            a -> {
              assertThat(a.type()).isEqualTo("Procurement");
              assertThat(a.version()).isEqualTo(1);
              assertThat(a.events()).hasSize(1);
            });
    assertThat(gateway.calls).containsExactly("2026-10-20/1", "2026-10-20/2");
    assertThat(runs.store.get(run.id()).status()).isEqualTo(RunStatus.DONE);
  }

  @Test
  void theWindowIsTheSaoPauloDayAsInstants() {
    var run = useCase.run(D20, D20, 6).getFirst();

    assertThat(run.windowStart()).isEqualTo(Instant.parse("2026-10-20T03:00:00Z"));
    assertThat(run.windowEnd()).isEqualTo(Instant.parse("2026-10-21T03:00:00Z"));
    assertThat(run.modalityId()).isEqualTo(6);
  }

  @Test
  void aRepeatedRunCountsUnchangedAndEmitsNothing() {
    gateway.stub(D20, 1, page(1, 1, snapshot(1), snapshot(2)));
    useCase.run(D20, D20, 6);
    var before = outbox.eventCount();

    var second = useCase.run(D20, D20, 6).getFirst();

    assertThat(second.discovered()).isZero();
    assertThat(second.updated()).isZero();
    assertThat(second.unchanged()).isEqualTo(2);
    assertThat(outbox.eventCount()).isEqualTo(before);
    assertThat(runs.store).hasSize(2);
  }

  @Test
  void aChangedNoticeCountsAsUpdatedAndEmitsItsEvents() {
    gateway.stub(D20, 1, page(1, 1, snapshot(1), snapshot(2)));
    useCase.run(D20, D20, 6);
    var before = outbox.appended.size();
    var newer = Instant.parse("2026-10-20T11:00:00Z");
    gateway.stub(D20, 1, page(1, 1, snapshot(1, 2, newer), snapshot(2)));

    var second = useCase.run(D20, D20, 6).getFirst();

    assertThat(second.updated()).isEqualTo(1);
    assertThat(second.unchanged()).isEqualTo(1);
    assertThat(second.discovered()).isZero();
    var added = outbox.appended.subList(before, outbox.appended.size());
    assertThat(added).hasSize(1);
    assertThat(added.getFirst().version()).isEqualTo(2);
    assertThat(added.getFirst().events()).hasSize(2);
  }

  @Test
  void aStaleSnapshotIsUnchanged() {
    gateway.stub(D20, 1, page(1, 1, snapshot(1)));
    useCase.run(D20, D20, 6);
    gateway.stub(D20, 1, page(1, 1, snapshot(1, 2, Instant.parse("2026-10-19T00:00:00Z"))));

    var second = useCase.run(D20, D20, 6).getFirst();

    assertThat(second.unchanged()).isEqualTo(1);
    assertThat(outbox.appended).hasSize(1);
  }

  @Test
  void aNoticeRepeatedOnTwoPagesIsDiscoveredOnce() {
    gateway.stub(D20, 1, page(1, 2, snapshot(1), snapshot(2)));
    gateway.stub(D20, 2, page(2, 2, snapshot(2), snapshot(3)));

    var run = useCase.run(D20, D20, 6).getFirst();

    assertThat(run.discovered()).isEqualTo(3);
    assertThat(run.unchanged()).isEqualTo(1);
    assertThat(run.fetched()).isEqualTo(4);
    assertThat(outbox.appended).hasSize(3);
  }

  @Test
  void eachDayInTheRangeGetsItsOwnRunInOrder() {
    gateway.stub(D20, 1, page(1, 1, snapshot(1)));
    gateway.stub(D21, 1, page(1, 1, snapshot(2)));
    gateway.stub(D22, 1, page(1, 1, snapshot(3)));

    var result = useCase.run(D20, D22, 6);

    assertThat(result).extracting(IngestionRun::status).containsOnly(RunStatus.DONE);
    assertThat(result)
        .extracting(IngestionRun::windowStart)
        .containsExactly(
            Instant.parse("2026-10-20T03:00:00Z"),
            Instant.parse("2026-10-21T03:00:00Z"),
            Instant.parse("2026-10-22T03:00:00Z"));
    assertThat(gateway.calls).containsExactly("2026-10-20/1", "2026-10-21/1", "2026-10-22/1");
    assertThat(procurements.store).hasSize(3);
  }

  @Test
  void aWindowWithoutDataFinishesDoneWithZeroCounters() {
    var run = useCase.run(D20, D20, 6).getFirst();

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.cursorPage()).isZero();
    assertThat(run.fetched()).isZero();
    assertThat(run.discovered()).isZero();
    assertThat(gateway.calls).containsExactly("2026-10-20/1");
    assertThat(outbox.appended).isEmpty();
  }

  @Test
  void aFailureOnPageThreeFailsTheRunAtCursorTwoAndSkipsLaterDays() {
    gateway.stub(D20, 1, page(1, 3, snapshot(1)));
    gateway.stub(D20, 2, page(2, 3, snapshot(2)));
    gateway.stub(D20, 3, new PncpUnavailableException(503, "retries exhausted", null));
    gateway.stub(D21, 1, page(1, 1, snapshot(9)));

    assertThatThrownBy(() -> useCase.run(D20, D21, 6))
        .isInstanceOf(BackfillFailedException.class)
        .hasMessageContaining("2026-10-20")
        .hasMessageContaining("page 3")
        .hasCauseInstanceOf(PncpUnavailableException.class);

    var run = runs.store.values().iterator().next();
    assertThat(runs.store).hasSize(1);
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.cursorPage()).isEqualTo(2);
    assertThat(run.fetched()).isEqualTo(2);
    assertThat(run.discovered()).isEqualTo(2);
    assertThat(run.error()).contains("PncpUnavailableException").contains("retries exhausted");
    assertThat(run.finishedAt()).isEqualTo(NOW);
    assertThat(gateway.calls).doesNotContain("2026-10-21/1");
    assertThat(procurements.store).hasSize(2);
  }

  @Test
  void aProtocolFailureNamesTheBadItemInTheRunError() {
    gateway.stub(
        D20, 1, new PncpProtocolException(0, "item 83021808000182-1-000007/2026: bad date", null));

    assertThatThrownBy(() -> useCase.run(D20, D20, 6))
        .isInstanceOf(BackfillFailedException.class)
        .hasCauseInstanceOf(PncpProtocolException.class);

    var run = runs.store.values().iterator().next();
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.cursorPage()).isZero();
    assertThat(run.error()).contains("83021808000182-1-000007/2026").contains("bad date");
  }

  @Test
  void runningAgainResumesAfterTheLastProcessedPage() {
    gateway.stub(D20, 1, page(1, 3, snapshot(1)));
    gateway.stub(D20, 2, page(2, 3, snapshot(2)));
    gateway.stub(D20, 3, new PncpUnavailableException(503, "down", null));
    assertThatThrownBy(() -> useCase.run(D20, D20, 6)).isInstanceOf(BackfillFailedException.class);
    var failedId = runs.store.keySet().iterator().next();
    gateway.calls.clear();
    gateway.stub(D20, 3, page(3, 3, snapshot(3)));

    var run = useCase.run(D20, D20, 6).getFirst();

    assertThat(gateway.calls).containsExactly("2026-10-20/3");
    assertThat(run.id()).isEqualTo(failedId);
    assertThat(runs.store).hasSize(1);
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.error()).isNull();
    assertThat(run.cursorPage()).isEqualTo(3);
    assertThat(run.fetched()).isEqualTo(3);
    assertThat(run.discovered()).isEqualTo(3);
    assertThat(outbox.appended).hasSize(3);
  }

  @Test
  void aFailureWhileSavingTheLastPageLeavesTheStoredCursorBehind() {
    gateway.stub(D20, 1, page(1, 2, snapshot(1)));
    gateway.stub(D20, 2, page(2, 2, snapshot(2)));
    // the page transaction fails at its last step: the stored cursor must not move to 2
    runs.failWhen = r -> r.cursorPage() == 2 && r.status() != RunStatus.FAILED;

    assertThatThrownBy(() -> useCase.run(D20, D20, 6))
        .isInstanceOf(BackfillFailedException.class)
        .hasMessageContaining("page 2")
        .hasCauseInstanceOf(IllegalStateException.class);

    var run = runs.store.values().iterator().next();
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.cursorPage()).isEqualTo(1);
    assertThat(run.fetched()).isEqualTo(1);
  }

  @Test
  void theLastPageFinishesTheRunInTheSameTransactionAsTheCursor() {
    gateway.stub(D20, 1, page(1, 2, snapshot(1)));
    gateway.stub(D20, 2, page(2, 2, snapshot(2)));
    runs.failWhen = r -> r.status() == RunStatus.DONE;

    assertThatThrownBy(() -> useCase.run(D20, D20, 6)).isInstanceOf(BackfillFailedException.class);
    var stored = runs.store.values().iterator().next();
    assertThat(stored.status()).isEqualTo(RunStatus.FAILED);
    assertThat(stored.cursorPage()).isEqualTo(1);
    gateway.calls.clear();
    runs.failWhen = r -> false;

    var run = useCase.run(D20, D20, 6).getFirst();

    assertThat(gateway.calls).containsExactly("2026-10-20/2");
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.cursorPage()).isEqualTo(2);
    assertThat(run.fetched()).isEqualTo(2);
  }

  @Test
  void whenMarkingTheRunFailedAlsoFailsTheOriginalCauseSurvives() {
    gateway.stub(D20, 1, new PncpUnavailableException(503, "down", null));
    runs.failWhen = r -> r.status() == RunStatus.FAILED;

    assertThatThrownBy(() -> useCase.run(D20, D20, 6))
        .isInstanceOf(BackfillFailedException.class)
        .hasCauseInstanceOf(PncpUnavailableException.class)
        .satisfies(
            e -> {
              assertThat(e.getCause().getSuppressed()).hasSize(1);
              assertThat(e.getCause().getSuppressed()[0])
                  .hasMessageContaining("cannot save the run");
            });
  }

  @Test
  void aSecondRunOfTheSameWindowIsRefusedWhileTheFirstIsInFlight() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    gateway.beforeCall =
        () -> {
          entered.countDown();
          await(release);
        };
    gateway.stub(D20, 1, page(1, 1, snapshot(1)));
    var first = new Thread(() -> useCase.run(D20, D20, 6));
    first.start();
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(useCase.isBusy(D20, D20, 6)).isTrue();
    assertThat(useCase.isBusy(D20, D21, 6)).isTrue();
    assertThat(useCase.isBusy(D21, D22, 6)).isFalse();
    assertThat(useCase.isBusy(D20, D20, 12)).isFalse();
    assertThatThrownBy(() -> useCase.run(D20, D20, 6))
        .isInstanceOf(BackfillWindowBusyException.class)
        .hasMessageContaining("2026-10-20");
    release.countDown();
    first.join(5000);

    assertThat(useCase.isBusy(D20, D20, 6)).isFalse();
    assertThat(runs.store).hasSize(1);
    assertThat(procurements.store).hasSize(1);
    assertThat(outbox.appended).hasSize(1);
  }

  @Test
  void theWindowIsFreeAgainAfterAFailure() {
    gateway.stub(D20, 1, new PncpUnavailableException(503, "down", null));
    assertThatThrownBy(() -> useCase.run(D20, D20, 6)).isInstanceOf(BackfillFailedException.class);

    assertThat(useCase.isBusy(D20, D20, 6)).isFalse();
  }

  @Test
  void isDoneTellsWhetherTheDayHasAFinishedRun() {
    assertThat(useCase.isDone(D20, 6)).isFalse();
    useCase.run(D20, D20, 6);

    assertThat(useCase.isDone(D20, 6)).isTrue();
    assertThat(useCase.isDone(D21, 6)).isFalse();
    assertThat(useCase.isDone(D20, 12)).isFalse();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
