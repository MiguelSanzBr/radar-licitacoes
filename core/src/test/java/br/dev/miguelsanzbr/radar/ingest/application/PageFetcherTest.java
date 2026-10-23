package br.dev.miguelsanzbr.radar.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class PageFetcherTest {

  private static PncpPage page(int number, int total) {
    return new PncpPage(List.of(), number, total, 0, total - number);
  }

  private static IntFunction<PncpPage> pagesOf(int total) {
    return number -> page(number, total);
  }

  private static List<Integer> drain(PageFetcher.Pages pages, int total) {
    var numbers = new ArrayList<Integer>();
    PncpPage result;
    do {
      result = pages.next();
      numbers.add(result.pageNumber());
    } while (!result.isLast() && numbers.size() < total + 5);
    return numbers;
  }

  @Test
  void rejectsAFetchAheadBelowOne() {
    assertThatThrownBy(() -> PageFetcher.virtualThreads(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fetchAhead");
  }

  @Test
  void deliversPagesInOrderEvenWhenLaterPagesFinishFirst() {
    var page3Fetched = new CountDownLatch(1);
    try (var fetcher = PageFetcher.virtualThreads(4)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                if (number == 2) {
                  // page 2 only completes once page 3 has been fetched
                  await(page3Fetched);
                }
                if (number == 3) {
                  page3Fetched.countDown();
                }
                return page(number, 5);
              });
      assertThat(drain(pages, 5)).containsExactly(1, 2, 3, 4, 5);
    }
  }

  @Test
  void neverAsksForMoreThanTheFirstPageBeforeItKnowsHowManyThereAre() {
    var requested = ConcurrentHashMap.<Integer>newKeySet();
    try (var fetcher = PageFetcher.virtualThreads(4)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                requested.add(number);
                return page(number, 1);
              });

      assertThat(pages.next().isLast()).isTrue();
      assertThat(requested).containsExactly(1);
    }
  }

  @Test
  void neverAsksBeyondTheLastPage() {
    var requested = ConcurrentHashMap.<Integer>newKeySet();
    try (var fetcher = PageFetcher.virtualThreads(8)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                requested.add(number);
                return page(number, 3);
              });
      assertThat(drain(pages, 3)).containsExactly(1, 2, 3);
    }
    assertThat(requested).containsExactlyInAnyOrder(1, 2, 3);
  }

  @Test
  void resumesAtTheGivenFirstPage() {
    try (var fetcher = PageFetcher.virtualThreads(2)) {
      assertThat(drain(fetcher.open(4, pagesOf(6)), 6)).containsExactly(4, 5, 6);
    }
  }

  @Test
  void fetchesAheadUpToTheLimitAndNoFurther() {
    var inFlight = new AtomicInteger();
    var peak = new AtomicInteger();
    try (var fetcher = PageFetcher.virtualThreads(3)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                var now = inFlight.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                sleep(50);
                inFlight.decrementAndGet();
                return page(number, 12);
              });
      assertThat(drain(pages, 12)).hasSize(12);
    }
    assertThat(peak.get()).isEqualTo(3);
  }

  @Test
  void aFetchAheadOfOneNeverFetchesTwoPagesAtOnce() {
    var inFlight = new AtomicInteger();
    var peak = new AtomicInteger();
    try (var fetcher = PageFetcher.virtualThreads(1)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                sleep(10);
                inFlight.decrementAndGet();
                return page(number, 4);
              });
      assertThat(drain(pages, 4)).hasSize(4);
    }
    assertThat(peak.get()).isEqualTo(1);
  }

  @Test
  void aFailureSurfacesWhenItsPageIsReachedWithTheOriginalException() {
    var boom = new IllegalStateException("page 3 down");
    try (var fetcher = PageFetcher.virtualThreads(4)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                if (number == 3) {
                  throw boom;
                }
                return page(number, 5);
              });

      assertThat(pages.next().pageNumber()).isEqualTo(1);
      assertThat(pages.next().pageNumber()).isEqualTo(2);
      assertThatThrownBy(pages::next).isSameAs(boom);
    }
  }

  @Test
  void anErrorIsNotWrapped() {
    var error = new AssertionError("fatal");
    try (var fetcher = PageFetcher.virtualThreads(1)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                throw error;
              });
      assertThatThrownBy(pages::next).isSameAs(error);
    }
  }

  @Test
  void closingThePagesInterruptsWhatIsStillInFlight() throws Exception {
    var started = new CountDownLatch(2);
    var interrupted = new CountDownLatch(1);
    try (var fetcher = PageFetcher.virtualThreads(3)) {
      var pages =
          fetcher.open(
              1,
              number -> {
                if (number == 1) {
                  return page(1, 9);
                }
                started.countDown();
                try {
                  new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                  interrupted.countDown();
                  Thread.currentThread().interrupt();
                }
                return page(number, 9);
              });
      pages.next();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      pages.close();

      assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void aThreadInterruptedWhileWaitingStopsTheReadAndKeepsTheFlag() {
    try (var fetcher = PageFetcher.virtualThreads(1)) {
      try (var pages =
          fetcher.open(
              1,
              number -> {
                sleep(5_000);
                return page(number, 2);
              })) {
        Thread.currentThread().interrupt();

        assertThatThrownBy(pages::next).isInstanceOf(CancellationException.class);
        assertThat(Thread.interrupted()).isTrue();
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
