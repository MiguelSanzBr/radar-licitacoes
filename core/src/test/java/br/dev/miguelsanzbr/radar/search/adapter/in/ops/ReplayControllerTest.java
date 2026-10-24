package br.dev.miguelsanzbr.radar.search.adapter.in.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.search.application.ReplayReport;
import br.dev.miguelsanzbr.radar.search.application.ReplayUseCase;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReplayControllerTest {

  private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");
  private static final ReplayReport REPORT = ReplayReport.of(50_000, START, START.plusSeconds(25));

  private final ReplayUseCase useCase = mock();
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ReplayController(useCase)).build();

  private static ReplayUseCase.Claim waitingFor(CountDownLatch release) {
    return () -> {
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return REPORT;
    };
  }

  private static ReplayUseCase.Claim failingWith(RuntimeException failure) {
    return () -> {
      throw failure;
    };
  }

  private String last() throws Exception {
    return mvc.perform(get("/ops/replay/last")).andReturn().getResponse().getContentAsString();
  }

  private void awaitStatus(String status) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!last().contains("\"status\":\"" + status + "\"")) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("GET /ops/replay/last never reported " + status + ": " + last());
      }
      Thread.sleep(10);
    }
  }

  @Test
  void thereIsNothingToReportBeforeAnyReplay() throws Exception {
    mvc.perform(get("/ops/replay/last")).andExpect(status().isNotFound());
  }

  @Test
  void acceptsAtOnceRunsOnAnotherThreadAndReportsDoneWithTheReport() throws Exception {
    var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    when(useCase.claim())
        .thenReturn(
            Optional.of(
                () -> {
                  thread.set(Thread.currentThread());
                  return REPORT;
                }));

    mvc.perform(post("/ops/replay"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
    verify(useCase).claim();
    awaitStatus("DONE");

    assertThat(thread.get()).isNotSameAs(Thread.currentThread());
    mvc.perform(get("/ops/replay/last"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.report.events").value(50_000))
        .andExpect(jsonPath("$.report.eventsPerSecond").value(2000.0))
        .andExpect(jsonPath("$.report.startedAt").exists())
        .andExpect(jsonPath("$.report.finishedAt").exists())
        .andExpect(jsonPath("$.report.duration").exists())
        .andExpect(jsonPath("$.error").doesNotExist());
  }

  @Test
  void whileOneRunsItIsReportedRunningAndASecondIsRefused() throws Exception {
    var release = new CountDownLatch(1);
    when(useCase.claim()).thenReturn(Optional.of(waitingFor(release)));

    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());

    mvc.perform(get("/ops/replay/last"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.report").doesNotExist());
    mvc.perform(post("/ops/replay"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("a replay is already running"));

    release.countDown();
    awaitStatus("DONE");
  }

  @Test
  void whileAnotherInstanceReplaysThePostIsRefusedAndNothingRuns() throws Exception {
    when(useCase.claim()).thenReturn(Optional.empty());

    mvc.perform(post("/ops/replay"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("a replay is already running on another instance"));

    mvc.perform(get("/ops/replay/last")).andExpect(status().isNotFound());
  }

  @Test
  void aNewReplayHidesThePreviousReport() throws Exception {
    var release = new CountDownLatch(1);
    when(useCase.claim())
        .thenReturn(Optional.of(() -> REPORT))
        .thenReturn(Optional.of(waitingFor(release)));

    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());
    awaitStatus("DONE");
    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());

    mvc.perform(get("/ops/replay/last"))
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.report").doesNotExist());
    release.countDown();
    awaitStatus("DONE");
  }

  @Test
  void aFailedReplayIsReportedWithItsErrorAndFreesTheSlot() throws Exception {
    var release = new CountDownLatch(1);
    when(useCase.claim())
        .thenReturn(Optional.of(failingWith(new IllegalStateException("broker gone"))))
        .thenReturn(Optional.of(waitingFor(release)));

    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());
    awaitStatus("FAILED");
    mvc.perform(get("/ops/replay/last"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.error").value("broker gone"))
        .andExpect(jsonPath("$.report").doesNotExist());

    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());
    mvc.perform(get("/ops/replay/last"))
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.error").doesNotExist());
    release.countDown();
    awaitStatus("DONE");
  }

  @Test
  void anErrorIsReportedFailedToo() throws Exception {
    var release = new CountDownLatch(1);
    when(useCase.claim())
        .thenReturn(
            Optional.of(
                () -> {
                  throw new AssertionError("x");
                }))
        .thenReturn(Optional.of(waitingFor(release)));

    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());
    awaitStatus("FAILED");
    mvc.perform(get("/ops/replay/last")).andExpect(jsonPath("$.error").value("x"));

    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());
    release.countDown();
    awaitStatus("DONE");
  }

  @Test
  void aFailureWithoutAMessageIsNamedByItsClass() throws Exception {
    when(useCase.claim()).thenReturn(Optional.of(failingWith(new IllegalStateException())));

    mvc.perform(post("/ops/replay")).andExpect(status().isAccepted());
    awaitStatus("FAILED");

    mvc.perform(get("/ops/replay/last"))
        .andExpect(jsonPath("$.error").value("IllegalStateException"));
  }
}
