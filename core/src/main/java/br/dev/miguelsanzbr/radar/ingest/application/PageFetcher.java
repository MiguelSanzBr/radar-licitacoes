package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Fetches the pages of one listing ahead of the code that processes them: up to {@code fetchAhead}
 * pages are being fetched or wait their turn while the previous one is processed, but {@link
 * Pages#next()} always hands them back in page order, so the transaction per page and the run's
 * cursor see exactly what a sequential read would.
 *
 * <p>Concurrency is only about latency. The gateway's rate limiter stays the one cap on requests
 * per second and its bulkhead the cap on calls in flight, so {@code fetchAhead} is set to the
 * bulkhead size: more tasks would only queue behind it.
 */
public final class PageFetcher implements AutoCloseable {

  private final ExecutorService executor;
  private final int fetchAhead;

  /** One virtual thread per page fetch, at most {@code fetchAhead} pages ahead. */
  public static PageFetcher virtualThreads(int fetchAhead) {
    return new PageFetcher(Executors.newVirtualThreadPerTaskExecutor(), fetchAhead);
  }

  /** Takes over {@code executor}: {@link #close()} closes it. */
  public PageFetcher(ExecutorService executor, int fetchAhead) {
    if (fetchAhead < 1) {
      throw new IllegalArgumentException("fetchAhead must be >= 1");
    }
    this.executor = executor;
    this.fetchAhead = fetchAhead;
  }

  /**
   * Starts a read at {@code firstPage}. Only that page is requested until its answer says how many
   * remain, so a one page window costs one request.
   */
  public Pages open(int firstPage, IntFunction<PncpPage> fetch) {
    return new Pages(firstPage, fetch);
  }

  @Override
  public void close() {
    executor.close();
  }

  /** The read of one listing; used by a single thread, which must {@link #close()} it. */
  public final class Pages implements AutoCloseable {

    private final Deque<Future<PncpPage>> inFlight = new ArrayDeque<>();
    private final IntFunction<PncpPage> fetch;
    private int nextToStart;
    private int lastKnown;
    private int nextToHandBack;

    private Pages(int firstPage, IntFunction<PncpPage> fetch) {
      this.fetch = fetch;
      this.nextToStart = firstPage;
      this.lastKnown = firstPage;
      this.nextToHandBack = firstPage;
    }

    /**
     * The next page in order, waiting for it if needed. A failed fetch is rethrown as itself, when
     * its page is reached.
     */
    public PncpPage next() {
      if (inFlight.isEmpty()) {
        start();
      }
      startWhileRoom();
      var result = await(inFlight.peek());
      inFlight.poll();
      lastKnown = nextToHandBack + result.remainingPages();
      nextToHandBack++;
      startWhileRoom();
      return result;
    }

    /** Interrupts the fetches still in flight. */
    @Override
    public void close() {
      inFlight.forEach(f -> f.cancel(true));
      inFlight.clear();
    }

    private void startWhileRoom() {
      while (inFlight.size() < fetchAhead && nextToStart <= lastKnown) {
        start();
      }
    }

    private void start() {
      var page = nextToStart++;
      inFlight.add(executor.submit(() -> fetch.apply(page)));
    }

    private static PncpPage await(Future<PncpPage> future) {
      try {
        return future.get();
      } catch (ExecutionException e) {
        // the fetch function throws no checked exception, so the cause is an Error or unchecked
        if (e.getCause() instanceof Error error) {
          throw error;
        }
        throw (RuntimeException) e.getCause();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException("interrupted while waiting for a PNCP page");
      }
    }
  }
}
