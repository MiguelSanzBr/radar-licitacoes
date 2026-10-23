package br.dev.miguelsanzbr.radar.ingest.application;

import static br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.page;
import static br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.Blocked;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.InMemoryProcurements;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.InMemoryRuns;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.RecordingMetrics;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCaseTest.RecordingOutbox;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionCursorRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpBlockedException;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionCursor;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.RunStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class IncrementalSyncUseCaseTest {

  /** 23:00 of 21 October in Sao Paulo, already 22 October in UTC. */
  private static final Instant NOW = Instant.parse("2026-10-22T02:00:00Z");

  private static final Duration OVERLAP = Duration.ofMinutes(15);
  private static final Instant WATERMARK = Instant.parse("2026-10-21T02:30:00Z");

  record Call(LocalDate from, LocalDate to, int modalityId, int page) {}

  static final class FakeUpdates implements PncpGateway {
    final Map<Integer, Object> pages = new ConcurrentHashMap<>();
    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    Instant blockedUntil;

    @Override
    public Optional<Instant> blockedUntil() {
      return Optional.ofNullable(blockedUntil);
    }

    @Override
    public PncpPage publications(LocalDate from, LocalDate to, int modalityId, int page) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PncpPage updates(LocalDate from, LocalDate to, int modalityId, int page) {
      calls.add(new Call(from, to, modalityId, page));
      return switch (pages.get(page)) {
        case null -> new PncpPage(List.of(), page, 0, 0, 0);
        case RuntimeException e -> throw e;
        case PncpPage p -> p;
        default -> throw new IllegalStateException("bad stub");
      };
    }
  }

  static final class InMemoryCursors implements IngestionCursorRepository {
    final Map<Integer, IngestionCursor> store = new HashMap<>();

    @Override
    public Optional<IngestionCursor> find(int modalityId) {
      return Optional.ofNullable(store.get(modalityId));
    }

    @Override
    public void save(IngestionCursor cursor) {
      store.put(cursor.modalityId(), cursor);
    }
  }

  private final FakeUpdates gateway = new FakeUpdates();
  private final InMemoryProcurements procurements = new InMemoryProcurements();
  private final InMemoryRuns runs = new InMemoryRuns();
  private final RecordingOutbox outbox = new RecordingOutbox();
  private final RecordingMetrics metrics = new RecordingMetrics();
  private final InMemoryCursors cursors = new InMemoryCursors();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final PageProcessor processor =
      new PageProcessor(
          procurements, runs, outbox, TransactionOperations.withoutTransaction(), metrics, clock);
  private final IncrementalSyncUseCase useCase = syncWith(1);

  private IncrementalSyncUseCase syncWith(int fetchAhead) {
    return new IncrementalSyncUseCase(
        gateway,
        cursors,
        runs,
        processor,
        PageFetcher.virtualThreads(fetchAhead),
        clock,
        Duration.ofDays(1),
        OVERLAP);
  }

  private void cursorAt(Instant watermark) {
    cursors.save(new IngestionCursor(6, watermark, OVERLAP, watermark));
  }

  @Test
  void withoutACursorTheFirstWindowLooksBackOneDayAndTheRunIsIncremental() {
    gateway.pages.put(1, page(1, 1, snapshot(1, 1, Instant.parse("2026-10-21T20:00:00Z"))));

    var run = useCase.sync(6);

    assertThat(gateway.calls)
        .containsExactly(new Call(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21), 6, 1));
    assertThat(run.kind()).isEqualTo(IngestionKind.INCREMENTAL);
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.windowStart()).isEqualTo(Instant.parse("2026-10-21T01:45:00Z"));
    assertThat(run.windowEnd()).isEqualTo(NOW);
    assertThat(run.discovered()).isEqualTo(1);
  }

  @Test
  void theWindowStartsOneOverlapBeforeTheWatermarkAndItsDaysAreSaoPauloDays() {
    cursorAt(WATERMARK);

    useCase.sync(6);

    // 02:15Z on the 21st is still the 20th in Sao Paulo; 02:00Z on the 22nd is the 21st
    assertThat(gateway.calls)
        .containsExactly(new Call(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21), 6, 1));
    assertThat(runs.store.values().iterator().next().windowStart())
        .isEqualTo(Instant.parse("2026-10-21T02:15:00Z"));
  }

  @Test
  void theWatermarkAdvancesToTheNewestUpdateOfAllPages() {
    cursorAt(WATERMARK);
    gateway.pages.put(
        1,
        page(
            1,
            2,
            snapshot(1, 1, Instant.parse("2026-10-21T10:00:00Z")),
            snapshot(2, 1, Instant.parse("2026-10-21T12:00:00Z"))));
    gateway.pages.put(2, page(2, 2, snapshot(3, 1, Instant.parse("2026-10-21T11:00:00Z"))));

    var run = useCase.sync(6);

    assertThat(run.cursorPage()).isEqualTo(2);
    assertThat(run.fetched()).isEqualTo(3);
    assertThat(cursors.store.get(6).watermark()).isEqualTo(Instant.parse("2026-10-21T12:00:00Z"));
    assertThat(cursors.store.get(6).updatedAt()).isEqualTo(NOW);
    assertThat(outbox.eventCount()).isEqualTo(3);
  }

  @Test
  void aWindowWithoutItemsKeepsTheWatermarkAndStillStampsTheCursor() {
    cursorAt(WATERMARK);

    var run = useCase.sync(6);

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.fetched()).isZero();
    assertThat(cursors.store.get(6).watermark()).isEqualTo(WATERMARK);
    assertThat(cursors.store.get(6).updatedAt()).isEqualTo(NOW);
  }

  @Test
  void aFirstSyncWithoutItemsStoresTheInitialCursor() {
    useCase.sync(6);

    assertThat(cursors.store.get(6).watermark()).isEqualTo(Instant.parse("2026-10-21T02:00:00Z"));
    assertThat(cursors.store.get(6).overlap()).isEqualTo(OVERLAP);
  }

  @Test
  void theWatermarkNeverGoesPastNow() {
    cursorAt(WATERMARK);
    gateway.pages.put(1, page(1, 1, snapshot(1, 1, NOW.plus(Duration.ofHours(3)))));

    useCase.sync(6);

    assertThat(cursors.store.get(6).watermark()).isEqualTo(NOW);
  }

  @Test
  void aClockThatWentBackwardsNeverMovesTheWatermarkBack() {
    var ahead = NOW.plus(Duration.ofHours(1));
    cursorAt(ahead);
    gateway.pages.put(1, page(1, 1, snapshot(1, 1, NOW.minus(Duration.ofHours(1)))));

    useCase.sync(6);

    assertThat(cursors.store.get(6).watermark()).isEqualTo(ahead);
    assertThat(cursors.store.get(6).updatedAt()).isEqualTo(NOW);
  }

  @Test
  void itemsOutsideTheWindowAreStillAppliedAndCountAsUnchangedWhenTheHashIsEqual() {
    cursorAt(WATERMARK);
    var old = snapshot(1, 1, Instant.parse("2026-10-19T10:00:00Z"));
    procurements.save(Procurement.discover(old, clock).procurement());
    gateway.pages.put(1, page(1, 1, old, snapshot(2, 1, Instant.parse("2026-10-21T05:00:00Z"))));

    var run = useCase.sync(6);

    assertThat(run.unchanged()).isEqualTo(1);
    assertThat(run.discovered()).isEqualTo(1);
    assertThat(outbox.eventCount()).isEqualTo(1);
    assertThat(cursors.store.get(6).watermark()).isEqualTo(Instant.parse("2026-10-21T05:00:00Z"));
  }

  @Test
  void aSecondSyncWithTheSameItemsEmitsNoEventAndCountsThemUnchanged() {
    gateway.pages.put(1, page(1, 1, snapshot(1, 1, Instant.parse("2026-10-21T20:00:00Z"))));
    useCase.sync(6);
    var eventsAfterFirst = outbox.eventCount();

    var second = useCase.sync(6);

    assertThat(second.unchanged()).isEqualTo(1);
    assertThat(second.discovered()).isZero();
    assertThat(outbox.eventCount()).isEqualTo(eventsAfterFirst);
  }

  @Test
  void aChangedItemCountsAsUpdatedAndEmitsItsEvents() {
    gateway.pages.put(1, page(1, 1, snapshot(1, 1, Instant.parse("2026-10-21T20:00:00Z"))));
    useCase.sync(6);
    gateway.pages.put(1, page(1, 1, snapshot(1, 2, Instant.parse("2026-10-21T21:00:00Z"))));

    var second = useCase.sync(6);

    assertThat(second.updated()).isEqualTo(1);
    assertThat(outbox.eventCount()).isEqualTo(3);
    assertThat(cursors.store.get(6).watermark()).isEqualTo(Instant.parse("2026-10-21T21:00:00Z"));
  }

  @Test
  void fetchingAheadAppliesPagesInOrderAndTheWatermarkStillTakesTheNewestUpdate() {
    cursorAt(WATERMARK);
    for (var n = 1; n <= 4; n++) {
      gateway.pages.put(
          n, page(n, 4, snapshot(n, 1, Instant.parse("2026-10-21T1" + n + ":00:00Z"))));
    }

    var run = syncWith(3).sync(6);

    assertThat(run.cursorPage()).isEqualTo(4);
    assertThat(run.fetched()).isEqualTo(4);
    assertThat(outbox.appended)
        .extracting(a -> a.events().getFirst().aggregateId())
        .containsExactly(
            BackfillUseCaseTest.id(1).value(),
            BackfillUseCaseTest.id(2).value(),
            BackfillUseCaseTest.id(3).value(),
            BackfillUseCaseTest.id(4).value());
    assertThat(cursors.store.get(6).watermark()).isEqualTo(Instant.parse("2026-10-21T14:00:00Z"));
  }

  @Test
  void aFailureOnALaterPageFailsTheRunAndLeavesTheCursorWhereItWas() {
    cursorAt(WATERMARK);
    gateway.pages.put(1, page(1, 2, snapshot(1, 1, Instant.parse("2026-10-21T12:00:00Z"))));
    var failure = new PncpUnavailableException(503, "down", null);
    gateway.pages.put(2, failure);

    assertThatThrownBy(() -> useCase.sync(6))
        .isInstanceOf(IncrementalSyncFailedException.class)
        .hasMessageContaining("modality 6 failed at page 2")
        .hasCause(failure);

    var run = runs.store.values().iterator().next();
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.cursorPage()).isEqualTo(1);
    assertThat(run.error()).contains("PncpUnavailableException");
    assertThat(cursors.store.get(6).watermark()).isEqualTo(WATERMARK);
    assertThat(cursors.store.get(6).updatedAt()).isEqualTo(WATERMARK);
  }

  @Test
  void itTellsWhetherTheGatewayIsBlocked() {
    assertThat(useCase.blockedUntil()).isEmpty();

    gateway.blockedUntil = NOW.plusSeconds(60);

    assertThat(useCase.blockedUntil()).contains(NOW.plusSeconds(60));
  }

  @Test
  void aBlockedPncpFailsTheRunLeavesTheCursorAndIsCounted() {
    cursorAt(WATERMARK);
    var until = NOW.plusSeconds(1800);
    var blocked = new PncpBlockedException(429, until, null);
    gateway.pages.put(1, blocked);

    assertThatThrownBy(() -> useCase.sync(6))
        .isInstanceOf(IncrementalSyncFailedException.class)
        .hasCause(blocked);

    var run = runs.store.values().iterator().next();
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.error()).contains("PNCP blocked");
    assertThat(cursors.store.get(6).watermark()).isEqualTo(WATERMARK);
    assertThat(metrics.blocked).containsExactly(new Blocked(429, until));
  }

  @Test
  void aFailureMarkingTheRunIsSuppressedUnderTheOriginalError() {
    var failure = new PncpUnavailableException(503, "down", null);
    gateway.pages.put(1, failure);
    runs.failWhen = run -> run.status() == RunStatus.FAILED;

    assertThatThrownBy(() -> useCase.sync(6)).hasCause(failure);
    assertThat(failure.getSuppressed())
        .singleElement(InstanceOfAssertFactories.THROWABLE)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("cannot save the run");
  }

  @Test
  void eachItemOutcomeIsCountedByKind() {
    cursorAt(WATERMARK);
    gateway.pages.put(1, page(1, 1, snapshot(1, 1, Instant.parse("2026-10-21T12:00:00Z"))));

    useCase.sync(6);

    assertThat(metrics.calls)
        .containsExactly(new BackfillUseCaseTest.Counted(IngestionKind.INCREMENTAL, 1, 0, 0));
  }
}
