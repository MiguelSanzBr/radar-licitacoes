package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.shared.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Loads a range of days from the PNCP, one window per day and one transaction per page: the page's
 * notices, their outbox events and the run's cursor commit together, so a crash resumes at the
 * first page that did not commit.
 */
public final class BackfillUseCase {

  private static final ZoneId PNCP_ZONE = ZoneId.of("America/Sao_Paulo");

  /**
   * Only the append call of the outbox; SpotBugs reads a stored "append" type as a mutable list.
   */
  @FunctionalInterface
  private interface EventSink {
    void append(String aggregateType, long aggregateVersion, List<? extends DomainEvent> events);
  }

  /** The window a run owns while it is in flight. */
  private record WindowKey(IngestionKind kind, int modalityId, Instant start) {}

  private final Set<WindowKey> running = ConcurrentHashMap.newKeySet();
  private final PncpGateway gateway;
  private final ProcurementRepository procurements;
  private final IngestionRunRepository runs;
  private final EventSink outbox;
  private final TransactionOperations transactions;
  private final Clock clock;

  public BackfillUseCase(
      PncpGateway gateway,
      ProcurementRepository procurements,
      IngestionRunRepository runs,
      OutboxAppender outbox,
      TransactionOperations transactions,
      Clock clock) {
    this.gateway = gateway;
    this.procurements = procurements;
    this.runs = runs;
    this.outbox = outbox::append;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * Runs the days from {@code from} to {@code to}, both included, stopping at the first failure.
   *
   * @throws BackfillFailedException when a day fails; later days are not attempted
   * @throws BackfillWindowBusyException when a day is already being backfilled by this process;
   *     with several app instances the lock would have to be a Postgres advisory lock (not done)
   */
  public List<IngestionRun> run(LocalDate from, LocalDate to, int modalityId) {
    var result = new ArrayList<IngestionRun>();
    for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
      result.add(runDay(day, modalityId));
    }
    return result;
  }

  /** Whether any day of the range is being backfilled right now by this process. */
  public boolean isBusy(LocalDate from, LocalDate to, int modalityId) {
    for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
      if (running.contains(key(day, modalityId))) {
        return true;
      }
    }
    return false;
  }

  /** Whether the day already has a finished backfill run. */
  public boolean isDone(LocalDate day, int modalityId) {
    return runs.existsDone(
        IngestionKind.BACKFILL, modalityId, windowStart(day), windowStart(day.plusDays(1)));
  }

  private static Instant windowStart(LocalDate day) {
    return day.atStartOfDay(PNCP_ZONE).toInstant();
  }

  private static WindowKey key(LocalDate day, int modalityId) {
    return new WindowKey(IngestionKind.BACKFILL, modalityId, windowStart(day));
  }

  private IngestionRun runDay(LocalDate day, int modalityId) {
    var key = key(day, modalityId);
    if (!running.add(key)) {
      throw new BackfillWindowBusyException(day, modalityId);
    }
    try {
      return runWindow(day, modalityId);
    } finally {
      running.remove(key);
    }
  }

  private IngestionRun runWindow(LocalDate day, int modalityId) {
    var start = windowStart(day);
    var end = windowStart(day.plusDays(1));
    var open = runs.findResumable(IngestionKind.BACKFILL, modalityId, start, end);
    open.ifPresent(r -> r.resume(clock));
    var run =
        runs.save(
            open.orElseGet(
                () -> IngestionRun.start(IngestionKind.BACKFILL, modalityId, start, end, clock)));
    var page = run.nextPage();
    try {
      while (true) {
        var result = gateway.publications(day, day, modalityId, page);
        if (result.totalPages() == 0) {
          run.finish(clock);
          return runs.save(run);
        }
        run = processPage(run, page, result);
        if (result.isLast()) {
          return run;
        }
        page++;
      }
    } catch (RuntimeException e) {
      try {
        fail(run, e);
      } catch (RuntimeException failure) {
        e.addSuppressed(failure);
      }
      throw new BackfillFailedException(day, page, e);
    }
  }

  /**
   * One transaction: the page's notices, their events and the run (cursor, and DONE on the last
   * page, so a finished page is never left with an unfinished run). Returns the saved run.
   */
  private IngestionRun processPage(IngestionRun run, int page, PncpPage result) {
    return transactions.execute(
        status -> {
          var discovered = 0;
          var updated = 0;
          for (var snapshot : result.items()) {
            // shortcut: one lookup per notice; findAllById per page would cut it to two queries
            var existing = procurements.findById(snapshot.id());
            Procurement procurement;
            List<? extends DomainEvent> events;
            if (existing.isEmpty()) {
              var discovery = Procurement.discover(snapshot, clock);
              procurement = discovery.procurement();
              events = discovery.events();
              discovered++;
            } else {
              procurement = existing.get();
              events = procurement.applySnapshot(snapshot, clock);
              if (!events.isEmpty()) {
                updated++;
              }
            }
            var saved = procurements.save(procurement);
            if (!events.isEmpty()) {
              outbox.append("Procurement", saved.revision(), events);
            }
          }
          var total = result.items().size();
          run.pageProcessed(page, total, discovered, updated, total - discovered - updated);
          if (result.isLast()) {
            run.finish(clock);
          }
          return runs.save(run);
        });
  }

  /**
   * Marks the stored run FAILED. The stored state is reloaded because a rolled back page may have
   * moved the cursor of the in-memory run past what was committed.
   */
  private void fail(IngestionRun run, RuntimeException cause) {
    var stored =
        runs.findResumable(run.kind(), run.modalityId(), run.windowStart(), run.windowEnd())
            .orElseThrow();
    stored.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage(), clock);
    runs.save(stored);
  }
}
