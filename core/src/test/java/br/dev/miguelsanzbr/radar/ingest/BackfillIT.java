package br.dev.miguelsanzbr.radar.ingest;

import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.PNCP;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.id;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.pageJson;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.pageRequest;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.stubPage;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillFailedException;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowBusyException;
import br.dev.miguelsanzbr.radar.perf.PinningProbe;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@BackfillSpringBootTest
class BackfillIT {

  private static final LocalDate D20 = LocalDate.of(2026, 10, 20);
  private static final LocalDate D21 = LocalDate.of(2026, 10, 21);

  @Autowired BackfillUseCase backfill;
  @Autowired JdbcTemplate jdbc;
  @Autowired BackfillTestConfig.FlakyRunRepository flaky;
  @Autowired TaskScheduler scheduler;

  @BeforeEach
  @AfterEach
  void clean() {
    flaky.failAtCursor.set(0);
    PNCP.resetAll();
    jdbc.execute(
        "TRUNCATE ingest.procurement, ingest.procurement_situation_history,"
            + " ingest.ingestion_run, outbox.outbox_event");
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private int pageRequests(String day, int page) {
    return PNCP.findAll(
            getRequestedFor(urlPathEqualTo(BackfillTestSupport.PUBLICATION))
                .withQueryParam("dataInicial", equalTo(day))
                .withQueryParam("pagina", equalTo(String.valueOf(page))))
        .size();
  }

  private List<Map<String, Object>> runs() {
    return jdbc.queryForList(
        "SELECT window_start, status, cursor_page, fetched, discovered, updated, unchanged, error"
            + " FROM ingest.ingestion_run ORDER BY id");
  }

  private void stubTwoDaysOfThreePages() {
    for (var page = 1; page <= 3; page++) {
      stubPage("20261020", page, 3, 100 + 3 * page, 101 + 3 * page, 102 + 3 * page);
      stubPage("20261021", page, 3, 200 + 3 * page, 201 + 3 * page, 202 + 3 * page);
    }
  }

  @Test
  void twoDaysOfThreePagesLandEveryNoticeOnceWithOneDiscoveredEventEach() {
    stubTwoDaysOfThreePages();

    var result = backfill.run(D20, D21, 6);

    assertThat(result).hasSize(2);
    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(18);
    assertThat(count("SELECT count(DISTINCT numero_controle_pncp) FROM ingest.procurement"))
        .isEqualTo(18);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(18);
    assertThat(
            count(
                "SELECT count(DISTINCT aggregate_id) FROM outbox.outbox_event"
                    + " WHERE event_type = 'ProcurementDiscovered'"))
        .isEqualTo(18);
    assertThat(runs())
        .hasSize(2)
        .allSatisfy(
            run -> {
              assertThat(run).containsEntry("status", "DONE").containsEntry("cursor_page", 3);
              assertThat(run).containsEntry("fetched", 9).containsEntry("discovered", 9);
              assertThat(run).containsEntry("updated", 0).containsEntry("unchanged", 0);
            });
  }

  @Test
  void failureOnPageThreeFailsTheRunAtCursorTwoAndARerunResumesWithoutRefetching() {
    stubPage("20261020", 1, 3, 1, 2, 3);
    stubPage("20261020", 2, 3, 4, 5, 6);
    PNCP.stubFor(
        pageRequest("20261020", 3)
            .inScenario("page3")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500).withBody("down")));
    PNCP.stubFor(
        pageRequest("20261020", 3)
            .inScenario("page3")
            .whenScenarioStateIs("fixed")
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(pageJson(3, 3, 7, 8, 9))));

    assertThatThrownBy(() -> backfill.run(D20, D21, 6))
        .isInstanceOf(BackfillFailedException.class)
        .hasMessageContaining("2026-10-20")
        .hasMessageContaining("page 3");

    assertThat(runs()).hasSize(1);
    assertThat(runs().getFirst())
        .containsEntry("status", "FAILED")
        .containsEntry("cursor_page", 2)
        .containsEntry("fetched", 6);
    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(6);
    assertThat(pageRequests("20261021", 1)).isZero();

    PNCP.setScenarioState("page3", "fixed");
    var result = backfill.run(D20, D20, 6);

    assertThat(result.getFirst().id()).isNotNull();
    assertThat(runs()).hasSize(1);
    assertThat(runs().getFirst())
        .containsEntry("status", "DONE")
        .containsEntry("cursor_page", 3)
        .containsEntry("fetched", 9)
        .containsEntry("discovered", 9)
        .containsEntry("error", null);
    assertThat(pageRequests("20261020", 1)).isEqualTo(1);
    assertThat(pageRequests("20261020", 2)).isEqualTo(1);
    assertThat(pageRequests("20261020", 3)).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(9);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(9);
  }

  @Test
  void runningTheSameRangeTwiceAddsNoRowAndNoEvent() {
    stubTwoDaysOfThreePages();
    backfill.run(D20, D21, 6);
    var events = count("SELECT count(*) FROM outbox.outbox_event");

    backfill.run(D20, D21, 6);

    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(18);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(events);
    assertThat(runs()).hasSize(4);
    assertThat(runs().subList(2, 4))
        .allSatisfy(
            run -> {
              assertThat(run).containsEntry("status", "DONE");
              assertThat(run).containsEntry("discovered", 0).containsEntry("updated", 0);
              assertThat(run).containsEntry("unchanged", 9);
            });
  }

  /**
   * PNCP pagination is unstable: while a pass walks the window, a notice moves from page 2 to page
   * 1 after page 1 was served (a single pass never sees it) and another one is pushed from page 1
   * onto page 2 (seen twice). The first pass loses notice 5; the second pass of the same window,
   * which rediscovery and the incremental overlap provide in production, recovers it. Notices are
   * stored once and each gets exactly one Discovered event.
   */
  @Test
  void singlePassLossOnUnstablePaginationIsCaughtBySecondPassWithoutDuplicates() {
    scenarioPage(1, Scenario.STARTED, "pass1-page1-served", pageJson(1, 2, 1, 2, 3));
    scenarioPage(2, "pass1-page1-served", "pass1-done", pageJson(2, 2, 3, 4, 6));
    scenarioPage(1, "pass1-done", "pass2-page1-served", pageJson(1, 2, 1, 2, 5));
    scenarioPage(2, "pass2-page1-served", "pass2-done", pageJson(2, 2, 3, 4, 6));

    var first = backfill.run(D20, D20, 6).getFirst();

    assertThat(first.fetched()).isEqualTo(6);
    assertThat(first.discovered()).isEqualTo(5);
    assertThat(first.unchanged()).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM ingest.procurement WHERE numero_controle_pncp = '"
                    + id(5)
                    + "'"))
        .isZero();

    var second = backfill.run(D20, D20, 6).getFirst();

    assertThat(second.discovered()).isEqualTo(1);
    assertThat(second.unchanged()).isEqualTo(5);
    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(6);
    assertThat(count("SELECT count(DISTINCT numero_controle_pncp) FROM ingest.procurement"))
        .isEqualTo(6);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(6);
    assertThat(
            count(
                "SELECT count(DISTINCT aggregate_id) FROM outbox.outbox_event"
                    + " WHERE event_type = 'ProcurementDiscovered'"))
        .isEqualTo(6);
  }

  @Test
  void aPageThatFailsHalfwayRollsBackItsNoticesEventsAndCursor() {
    stubPage("20261020", 1, 2, 1, 2, 3);
    stubPage("20261020", 2, 2, 4, 5, 6);
    // saving the run with cursor 2 is the last step of the page 2 transaction
    flaky.failAtCursor.set(2);

    assertThatThrownBy(() -> backfill.run(D20, D20, 6))
        .isInstanceOf(BackfillFailedException.class)
        .hasMessageContaining("page 2");

    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM ingest.procurement WHERE numero_controle_pncp IN ('"
                    + id(4)
                    + "','"
                    + id(5)
                    + "','"
                    + id(6)
                    + "')"))
        .isZero();
    assertThat(runs().getFirst())
        .containsEntry("status", "FAILED")
        .containsEntry("cursor_page", 1)
        .containsEntry("fetched", 3);

    flaky.failAtCursor.set(0);
    backfill.run(D20, D20, 6);

    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(6);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(6);
    assertThat(runs().getFirst()).containsEntry("status", "DONE").containsEntry("cursor_page", 2);
  }

  @Test
  void twoParallelRunsOfTheSameWindowLetExactlyOneProceed() throws Exception {
    PNCP.stubFor(
        pageRequest("20261020", 1)
            .willReturn(
                aResponse()
                    .withFixedDelay(800)
                    .withHeader("Content-Type", "application/json")
                    .withBody(pageJson(1, 1, 1, 2, 3))));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<?> a = pool.submit(() -> await(start, () -> backfill.run(D20, D20, 6)));
      Future<?> b = pool.submit(() -> await(start, () -> backfill.run(D20, D20, 6)));
      start.countDown();

      var failures = 0;
      for (var future : List.of(a, b)) {
        try {
          future.get();
        } catch (java.util.concurrent.ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(BackfillWindowBusyException.class);
          failures++;
        }
      }
      assertThat(failures).isEqualTo(1);
    }

    assertThat(runs()).hasSize(1);
    assertThat(runs().getFirst()).containsEntry("status", "DONE").containsEntry("fetched", 3);
    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(3);
    assertThat(pageRequests("20261020", 1)).isEqualTo(1);
  }

  private static void await(CountDownLatch start, Runnable action) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    action.run();
  }

  /**
   * Boot's virtual-thread scheduler would run every fixed-delay task on one platform thread, so a
   * slow task would starve the outbox relay. The app declares a pool of its own instead.
   */
  @Test
  void scheduledTasksGetAPoolOfFourSoASlowOneNeverStarvesTheRelay() {
    assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
    assertThat(
            ((ThreadPoolTaskScheduler) scheduler)
                .getScheduledThreadPoolExecutor()
                .getCorePoolSize())
        .isEqualTo(4);
  }

  @Test
  void aFixedDelayTaskThatBlocksDoesNotStopAnotherOne() throws Exception {
    var release = new CountDownLatch(1);
    var blocked = new CountDownLatch(1);
    var ticks = new AtomicInteger();
    var tickThread = new AtomicReference<String>();
    var blocker =
        scheduler.scheduleWithFixedDelay(
            () -> {
              blocked.countDown();
              await(release, () -> {});
            },
            Duration.ofMillis(10));
    var ticker =
        scheduler.scheduleWithFixedDelay(
            () -> {
              tickThread.set(Thread.currentThread().getName());
              ticks.incrementAndGet();
            },
            Duration.ofMillis(10));
    try {
      assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
      var seen = ticks.get();
      var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (ticks.get() < seen + 3 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }

      assertThat(ticks.get()).isGreaterThanOrEqualTo(seen + 3);
      assertThat(tickThread.get()).startsWith("radar-sched-");
    } finally {
      release.countDown();
      blocker.cancel(true);
      ticker.cancel(true);
    }
  }

  @Test
  void pagesOfADayAreFetchedConcurrentlyYetLandInOrder() {
    for (var page = 1; page <= 8; page++) {
      var n = page;
      PNCP.stubFor(
          pageRequest("20261020", page)
              .willReturn(
                  aResponse()
                      .withFixedDelay(300)
                      .withHeader("Content-Type", "application/json")
                      .withBody(pageJson(n, 8, 10 * n, 10 * n + 1))));
    }

    var started = System.nanoTime();
    var run = backfill.run(D20, D20, 6).getFirst();
    var elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertThat(run.cursorPage()).isEqualTo(8);
    assertThat(run.discovered()).isEqualTo(16);
    // one page, then two waves of four (the bulkhead): about 900 ms against 2400 ms in sequence
    assertThat(elapsedMillis).isLessThan(1800);
    assertThat(
            jdbc.queryForList(
                "SELECT aggregate_id FROM outbox.outbox_event ORDER BY id", String.class))
        .isSorted();
  }

  @Test
  void noVirtualThreadIsPinnedForLongOnTheIngestionPath() throws Exception {
    for (var page = 1; page <= 6; page++) {
      stubPage("20261020", page, 6, 10 * page, 10 * page + 1);
      stubPage("20261021", page, 6, 100 + 10 * page, 101 + 10 * page);
    }

    var pins = PinningProbe.pinsDuring(() -> backfill.run(D20, D21, 6));

    assertThat(pins)
        .as("pins of 20 ms or more through the ingestion code")
        .noneMatch(pin -> PinningProbe.passesThrough(pin, "br.dev.miguelsanzbr.radar.ingest."));
  }

  private void scenarioPage(int page, String from, String to, String body) {
    PNCP.stubFor(
        pageRequest("20261020", page)
            .inScenario("unstable")
            .whenScenarioStateIs(from)
            .willSetStateTo(to)
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }
}
