package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Backpressure on a real broker and database: the projector is made slow on purpose, the container
 * pauses (the gauge {@code search_projector_paused} goes to 1 and nothing is projected while it
 * stays so), resumes after the cool-down, and every event ends up projected. Its own topic and
 * group keep it apart from what other test classes leave on the shared broker.
 *
 * <p>Run with {@code make messaging-test}; it is tagged {@code messaging}.
 */
@Tag("messaging")
@KafkaSpringBootTest
@TestPropertySource(
    properties = {
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-backpressure-it",
      "radar.outbox.topics.Procurement=radar.procurement.backpressure-it.v1",
      "radar.search.projector.backpressure.slow-after=20ms",
      "radar.search.projector.backpressure.window=10",
      "radar.search.projector.backpressure.pause-for=2s",
      "radar.search.projector.backpressure.check-interval=100ms",
      // why: a fresh 12 partition topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class ProjectorBackpressureIT {

  private static final String TOPIC = "radar.procurement.backpressure-it.v1";
  private static final int EVENTS = 300;
  private static final int SLOW_CALLS = 40;
  private static final long SLOW_MILLIS = 40;

  @Autowired KafkaTemplate<String, String> template;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry meters;
  @Autowired KafkaListenerEndpointRegistry registry;
  @MockitoSpyBean ProcurementProjector projector;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  /** Every partition of the fresh topic must accept writes before the test relies on them. */
  @BeforeEach
  void topicIsReady() {
    var probe =
        envelope(
            "Probe", closed("probe", id(0), 1), JsonMapper.builder().build().createObjectNode());
    await()
        .atMost(Duration.ofSeconds(150))
        .ignoreExceptions()
        .until(
            () -> {
              for (var partition = 0; partition < 12; partition++) {
                template.send(TOPIC, partition, "probe", probe).get(10, TimeUnit.SECONDS);
              }
              return true;
            });
  }

  private double paused() {
    return meters.get("search_projector_paused").gauge().value();
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM search." + table, Integer.class);
  }

  @Test
  void aSlowProjectorPausesTheContainerThenResumesAndLosesNothing() throws Exception {
    var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() <= SLOW_CALLS) {
                Thread.sleep(SLOW_MILLIS);
              }
              return invocation.callRealMethod();
            })
        .when(projector)
        .project(any(), anyLong());
    var sends = new ArrayList<CompletableFuture<?>>();
    for (var i = 0; i < EVENTS; i++) {
      var event = discovered("bp" + i, view(id(i), "Aquisição " + i, "1000.00", 1), 1);
      sends.add(template.send(TOPIC, event.aggregateId(), envelope(event)));
    }
    for (var send : sends) {
      send.get(120, TimeUnit.SECONDS);
    }

    var pauses = 0;
    var pauseMillis = new ArrayList<Long>();
    var callsAtEachPause = new ArrayList<Integer>();
    var stalledCalls = new ArrayList<Integer>();
    var deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
    var wasPaused = false;
    var pausedAt = 0L;
    var callsAtPause = 0;
    while (count("processed_event") < EVENTS && System.nanoTime() < deadline) {
      var now = paused() == 1.0;
      if (now && !wasPaused) {
        pauses++;
        pausedAt = System.nanoTime();
        // the record in flight when the pause was requested may still finish
        Thread.sleep(300);
        callsAtPause = calls.get();
        callsAtEachPause.add(callsAtPause);
      }
      if (now) {
        stalledCalls.add(calls.get() - callsAtPause);
      }
      if (!now && wasPaused) {
        pauseMillis.add(Duration.ofNanos(System.nanoTime() - pausedAt).toMillis());
      }
      wasPaused = now;
      Thread.sleep(10);
    }

    assertThat(count("processed_event")).as("inbox rows").isEqualTo(EVENTS);
    assertThat(count("procurement_search_view")).as("view rows").isEqualTo(EVENTS);
    assertThat(pauses).as("times the container paused").isPositive();
    assertThat(stalledCalls).as("projections while paused").isNotEmpty().containsOnly(0);
    assertThat(pauseMillis)
        .as("each pause lasted at least the cool-down")
        .allMatch(ms -> ms >= 1_500);
    await().atMost(Duration.ofSeconds(30)).until(() -> paused() == 0.0);
    assertThat(registry.getListenerContainer(ProcurementEventsListener.ID).isContainerPaused())
        .isFalse();
    var report =
        ("pauses %d at calls %s, pause durations ms %s, slow calls %d of %d events, calls to the"
                + " projector %d")
            .formatted(pauses, callsAtEachPause, pauseMillis, SLOW_CALLS, EVENTS, calls.get());
    System.out.println("BACKPRESSURE " + report);
    Files.writeString(
        Path.of("target", "backpressure-report.txt"), report + "\n", StandardCharsets.UTF_8);
  }
}
