package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.eventId;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Order by key on a real broker and database. Five versions of each of 200 procurements are
 * published interleaved across the keys. The events of a key must reach the projector in order, and
 * the stored {@code source_version} must never go down: not when an event goes to the dead letters
 * and is reprocessed after newer versions were projected, not when an older version is published
 * again with a new event id, and not when the very same event is delivered twice.
 *
 * <p>Run with {@code make messaging-test}; it is tagged {@code messaging}. Its own topics and
 * groups keep it apart from the other test classes.
 */
@Tag("messaging")
@KafkaSpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "radar.outbox.topics.Procurement=radar.procurement.ordering-it.v1",
      "radar.outbox.topics.Alert=radar.alert.ordering-it.v1",
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-ordering-it",
      "radar.ops.dlq-recorder.enabled=true",
      "radar.ops.dlq-recorder.group=ops-dlq-recorder-ordering-it",
      // why: a fresh 12 partition topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class ProjectorOrderingIT {

  private static final String TOPIC = "radar.procurement.ordering-it.v1";
  private static final String DLQ = TOPIC + ".dlq";
  private static final int KEYS = 200;
  private static final int VERSIONS = 5;
  private static final int POISONED_KEY = 7;
  private static final int POISONED_VERSION = 2;

  @Autowired KafkaTemplate<String, String> template;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoSpyBean ProcurementProjector projector;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE ops.dlq_message, search.procurement_search_view,"
            + " search.procurement_detail_view, search.processed_event,"
            + " search.projection_checkpoint, search.procurement_stats_view");
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

  private static String text(int key, int version) {
    return "Objeto v%d da licitação %d".formatted(version, key);
  }

  private static UUID eventOf(int key, int version) {
    return eventId("ord-%d-%d".formatted(key, version));
  }

  private static ProcurementEvent versionOf(int key, int version) {
    var view = view(id(key), text(key, version), "1000.00", 1, version, T0.plusSeconds(version));
    return version == 1
        ? discovered("ord-%d-%d".formatted(key, version), view, version)
        : updated("ord-%d-%d".formatted(key, version), view, version);
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private List<Integer> storedVersion(String id) {
    return jdbc.queryForList(
        "SELECT source_version FROM search.procurement_search_view"
            + " WHERE numero_controle_pncp = ?",
        Integer.class,
        id);
  }

  private void send(List<ProcurementEvent> events) throws Exception {
    var sends = new ArrayList<CompletableFuture<?>>();
    for (var event : events) {
      sends.add(template.send(TOPIC, event.aggregateId(), envelope(event)));
    }
    for (var send : sends) {
      send.get(120, TimeUnit.SECONDS);
    }
  }

  @Test
  void versionsOfAKeyArriveInOrderAndTheStoredVersionNeverGoesDown() throws Exception {
    var failing = new AtomicBoolean(true);
    var calls = new AtomicInteger();
    var arrivals = new ConcurrentHashMap<String, List<Long>>();
    var stored = new ConcurrentHashMap<String, List<Integer>>();
    var duplicates = new AtomicInteger();
    doAnswer(
            invocation -> {
              ProcurementEvent event = invocation.getArgument(0);
              calls.incrementAndGet();
              arrivals
                  .computeIfAbsent(
                      event.aggregateId(), k -> Collections.synchronizedList(new ArrayList<>()))
                  .add(event.aggregateVersion());
              if (failing.get()
                  && event.eventId().equals(eventOf(POISONED_KEY, POISONED_VERSION))) {
                throw new IllegalArgumentException("rejected until fixed");
              }
              var outcome = (ProjectionOutcome) invocation.callRealMethod();
              if (outcome == ProjectionOutcome.DUPLICATE) {
                duplicates.incrementAndGet();
              }
              storedVersion(event.aggregateId())
                  .forEach(
                      v ->
                          stored
                              .computeIfAbsent(
                                  event.aggregateId(),
                                  k -> Collections.synchronizedList(new ArrayList<>()))
                              .add(v));
              return outcome;
            })
        .when(projector)
        .project(any(), anyLong());

    // 1. versions 1..5 of every key, interleaved across the keys; one event is rejected
    var interleaved = new ArrayList<ProcurementEvent>();
    for (var version = 1; version <= VERSIONS; version++) {
      for (var key = 0; key < KEYS; key++) {
        interleaved.add(versionOf(key, version));
      }
    }
    var started = System.nanoTime();
    send(interleaved);
    var firstEvents = KEYS * VERSIONS - 1;
    await()
        .atMost(Duration.ofMinutes(3))
        .until(
            () ->
                count("SELECT count(*) FROM search.processed_event") == firstEvents
                    && count("SELECT count(*) FROM ops.dlq_message") == 1);
    var firstPass = Duration.ofNanos(System.nanoTime() - started);

    for (var key = 0; key < KEYS; key++) {
      assertThat(arrivals.get(id(key)))
          .as("versions that reached the projector for key %d", key)
          .containsExactly(1L, 2L, 3L, 4L, 5L);
    }
    assertThat(stored.get(id(POISONED_KEY)))
        .as("stored versions of the key whose version 2 went to the dead letters")
        .containsExactly(1, 3, 4, 5);
    assertThat(count("SELECT count(*) FROM search.procurement_search_view")).isEqualTo(KEYS);

    // 2. the operator reprocesses it: version 2 arrives after version 5 and is ignored by the guard
    failing.set(false);
    mvc.perform(post("/ops/dlq/" + DLQ + "/reprocess").with(Auth.operator()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.count").value(1));
    await()
        .atMost(Duration.ofMinutes(2))
        .until(() -> count("SELECT count(*) FROM search.processed_event") == KEYS * VERSIONS);
    assertThat(arrivals.get(id(POISONED_KEY)))
        .as("the reprocessed version came last")
        .containsExactly(1L, 2L, 3L, 4L, 5L, 2L);
    assertThat(stored.get(id(POISONED_KEY))).containsExactly(1, 3, 4, 5, 5);

    // 3. older versions published again, with new event ids, and the same event twice
    var late = new ArrayList<ProcurementEvent>();
    for (var key = 0; key < 10; key++) {
      late.add(
          updated(
              "stale-" + key,
              view(id(key), "Objeto antigo " + key, "1.00", 1, 3, T0.plusSeconds(3)),
              3));
    }
    for (var key = 10; key < 20; key++) {
      late.add(situationChanged("stale-situation-" + key, id(key), 2, 1, 4));
    }
    for (var key = 20; key < 30; key++) {
      late.add(versionOf(key, VERSIONS));
    }
    var callsBefore = calls.get();
    send(late);
    await().atMost(Duration.ofMinutes(2)).until(() -> calls.get() == callsBefore + late.size());
    await()
        .atMost(Duration.ofMinutes(2))
        .until(() -> count("SELECT count(*) FROM search.processed_event") == KEYS * VERSIONS + 20);

    // nothing went back: every key holds version 5, its text and its status
    assertThat(
            count("SELECT count(*) FROM search.procurement_search_view WHERE source_version = 5"))
        .isEqualTo(KEYS);
    assertThat(
            count("SELECT count(*) FROM search.procurement_search_view WHERE status = 'PUBLISHED'"))
        .isEqualTo(KEYS);
    assertThat(
            count("SELECT count(*) FROM search.procurement_detail_view WHERE source_version = 5"))
        .isEqualTo(KEYS);
    for (var key = 0; key < KEYS; key++) {
      var text =
          jdbc.queryForObject(
              "SELECT object_description FROM search.procurement_search_view"
                  + " WHERE numero_controle_pncp = ?",
              String.class,
              id(key));
      assertThat(text).as("object of key %d", key).isEqualTo(text(key, VERSIONS));
    }
    Map<String, List<Integer>> regressions = new ConcurrentHashMap<>();
    stored.forEach(
        (key, versions) -> {
          for (var i = 1; i < versions.size(); i++) {
            if (versions.get(i) < versions.get(i - 1)) {
              regressions.computeIfAbsent(key, k -> new ArrayList<>()).add(versions.get(i));
            }
          }
        });
    assertThat(regressions).as("stored versions that went down").isEmpty();
    assertThat(duplicates.get()).as("the same event delivered twice").isEqualTo(10);

    var report =
        ("%d keys x %d versions interleaved (%d events), all in order at the projector; version %d of"
                + " key %d dead-lettered and reprocessed after version %d, ignored by the guard; %d"
                + " older versions and %d older situation changes published again, ignored; %d"
                + " duplicates absorbed by the inbox; stored version went down %d times; first pass"
                + " %.1f s; samples of the stored version %d")
            .formatted(
                KEYS,
                VERSIONS,
                KEYS * VERSIONS,
                POISONED_VERSION,
                POISONED_KEY,
                VERSIONS,
                10,
                10,
                duplicates.get(),
                regressions.size(),
                firstPass.toMillis() / 1000.0,
                stored.values().stream().mapToInt(List::size).sum());
    System.out.println("ORDERING " + report);
    Files.writeString(
        Path.of("target", "ordering-report.txt"), report + "\n", StandardCharsets.UTF_8);
  }
}
