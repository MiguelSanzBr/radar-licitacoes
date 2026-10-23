package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads a range of days from the PNCP, one window per day and one transaction per page: the page's
 * notices, their outbox events and the run's cursor commit together, so a crash resumes at the
 * first page that did not commit. Pages are fetched ahead (see {@link PageFetcher}) but always
 * processed in page order.
 */
public final class BackfillUseCase {

  private static final ZoneId PNCP_ZONE = ZoneId.of("America/Sao_Paulo");

  /** The window a run owns while it is in flight. */
  private record WindowKey(IngestionKind kind, int modalityId, Instant start) {}

  private final Set<WindowKey> running = ConcurrentHashMap.newKeySet();
  private final PncpGateway gateway;
  private final IngestionRunRepository runs;
  private final PageProcessor pages;
  private final PageFetcher fetcher;
  private final Clock clock;

  public BackfillUseCase(
      PncpGateway gateway,
      IngestionRunRepository runs,
      PageProcessor pages,
      PageFetcher fetcher,
      Clock clock) {
    this.gateway = gateway;
    this.runs = runs;
    this.pages = pages;
    this.fetcher = fetcher;
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

  /** When the PNCP's block of the client ends, while one is in force. */
  public Optional<Instant> blockedUntil() {
    return gateway.blockedUntil();
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
    var reading = fetcher.open(page, p -> gateway.publications(day, day, modalityId, p));
    try {
      while (true) {
        var result = reading.next();
        if (result.totalPages() == 0) {
          return pages.finishEmpty(run);
        }
        run = pages.process(run, page, result);
        if (result.isLast()) {
          return run;
        }
        page++;
      }
    } catch (RuntimeException e) {
      pages.fail(run, e);
      throw new BackfillFailedException(day, page, e);
    } finally {
      reading.close();
    }
  }
}
