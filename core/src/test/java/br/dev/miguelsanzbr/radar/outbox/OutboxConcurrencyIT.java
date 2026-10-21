package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@OutboxSpringBootTest
class OutboxConcurrencyIT {

  private static final int EVENTS = 10_000;

  @Autowired OutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired MeterRegistry registry;
  @Autowired OutboxProperties properties;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
  }

  /** One relay thread: every relayOnce call is a cycle, recorded as the ids it published. */
  private List<List<Long>> drain(
      OutboxRelay own, InMemoryEventPublisher publisher, CountDownLatch go)
      throws InterruptedException {
    var cycles = new ArrayList<List<Long>>();
    go.await();
    while (OutboxRows.count(jdbc, "published_at IS NULL") > 0) {
      var before = publisher.published().size();
      own.relayOnce();
      cycles.add(publisher.publishedIds().subList(before, publisher.published().size()));
    }
    return cycles;
  }

  @Test
  void twoConcurrentRelaysPublishEveryEventExactlyOnceAndNeverShareACycle() throws Exception {
    jdbc.update(
        """
        INSERT INTO outbox.outbox_event
          (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers)
        SELECT gen_random_uuid(), 'Procurement', 'agg-' || (g % 500), 'ProcurementUpdated', 1,
               '{}'::jsonb, '{}'::jsonb
        FROM generate_series(1, ?) g
        """,
        EVENTS);
    var publisherA = new InMemoryEventPublisher();
    var publisherB = new InMemoryEventPublisher();
    publisherA.latency(Duration.ofMillis(1));
    publisherB.latency(Duration.ofMillis(1));
    var relayA = new OutboxRelay(jdbc, transactionManager, publisherA, properties, registry);
    var relayB = new OutboxRelay(jdbc, transactionManager, publisherB, properties, registry);
    var go = new CountDownLatch(1);

    var started = System.nanoTime();
    var a = CompletableFuture.supplyAsync(() -> run(relayA, publisherA, go));
    var b = CompletableFuture.supplyAsync(() -> run(relayB, publisherB, go));
    go.countDown();
    var cyclesA = a.get();
    var cyclesB = b.get();
    var millis = (System.nanoTime() - started) / 1_000_000;

    var idsA = publisherA.publishedIds();
    var idsB = publisherB.publishedIds();
    var union = new HashSet<Long>(idsA);
    union.addAll(idsB);
    var duplicates = idsA.size() + idsB.size() - union.size();
    var sharedCycleIds = new HashSet<>(idsA);
    sharedCycleIds.retainAll(idsB);

    System.out.printf(
        "OUTBOX_CONCURRENCY events=%d relayA=%d relayB=%d cyclesA=%d cyclesB=%d duplicates=%d timeMs=%d%n",
        EVENTS, idsA.size(), idsB.size(), cyclesA.size(), cyclesB.size(), duplicates, millis);

    assertThat(union).hasSize(EVENTS);
    assertThat(duplicates).isZero();
    assertThat(sharedCycleIds).isEmpty();
    assertThat(idsA).isNotEmpty();
    assertThat(idsB).isNotEmpty();
    assertThat(OutboxRows.count(jdbc, "published_at IS NULL")).isZero();
    assertThat(millis).isLessThan(60_000);
    // every cycle is one bounded batch published in id order
    for (var cycle : Stream.concat(cyclesA.stream(), cyclesB.stream()).toList()) {
      assertThat(cycle).hasSizeLessThanOrEqualTo(properties.batchSize()).isSorted();
    }
  }

  private List<List<Long>> run(
      OutboxRelay own, InMemoryEventPublisher publisher, CountDownLatch go) {
    try {
      return drain(own, publisher, go);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
