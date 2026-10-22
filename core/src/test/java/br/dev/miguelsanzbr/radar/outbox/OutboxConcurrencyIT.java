package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
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

  /** One relay thread: every relayOnce call is a cycle, recorded as how many messages it sent. */
  private List<Integer> drain(OutboxRelay own, CountDownLatch go) throws InterruptedException {
    var cycles = new ArrayList<Integer>();
    go.await();
    while (OutboxRows.count(jdbc, "published_at IS NULL") > 0) {
      cycles.add(own.relayOnce());
    }
    return cycles;
  }

  @Test
  void twoConcurrentRelaysAreSerializedSoEveryAggregateIsPublishedOnceAndInIdOrder()
      throws Exception {
    jdbc.update(
        """
        INSERT INTO outbox.outbox_event
          (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers)
        SELECT gen_random_uuid(), 'Procurement', 'agg-' || (g % 50), 'ProcurementUpdated', 1,
               '{}'::jsonb, '{}'::jsonb
        FROM generate_series(1, ?) g
        """,
        EVENTS);
    // one shared record: its order is the order in which consumers would see the events
    var publisher = new InMemoryEventPublisher();
    publisher.latency(Duration.ofMillis(1));
    var relayA = new OutboxRelay(jdbc, transactionManager, publisher, properties, registry);
    var relayB = new OutboxRelay(jdbc, transactionManager, publisher, properties, registry);
    var go = new CountDownLatch(1);

    var started = System.nanoTime();
    var a = CompletableFuture.supplyAsync(() -> run(relayA, go));
    var b = CompletableFuture.supplyAsync(() -> run(relayB, go));
    go.countDown();
    var cyclesA = a.get();
    var cyclesB = b.get();
    var millis = (System.nanoTime() - started) / 1_000_000;

    var published = publisher.published();
    var ids = published.stream().map(OutboxMessage::id).toList();
    var idsByAggregate =
        published.stream()
            .collect(
                Collectors.groupingBy(
                    OutboxMessage::aggregateId,
                    Collectors.mapping(OutboxMessage::id, Collectors.toList())));
    var skippedCycles =
        Stream.concat(cyclesA.stream(), cyclesB.stream()).filter(n -> n == 0).count();

    System.out.printf(
        "OUTBOX_CONCURRENCY events=%d aggregates=%d published=%d distinct=%d cyclesA=%d cyclesB=%d"
            + " skippedCycles=%d timeMs=%d%n",
        EVENTS,
        idsByAggregate.size(),
        ids.size(),
        new HashSet<>(ids).size(),
        cyclesA.size(),
        cyclesB.size(),
        skippedCycles,
        millis);

    assertThat(ids).hasSize(EVENTS).doesNotHaveDuplicates();
    assertThat(idsByAggregate).hasSize(50);
    idsByAggregate.values().forEach(aggregateIds -> assertThat(aggregateIds).isSorted());
    assertThat(skippedCycles).isPositive();
    assertThat(OutboxRows.count(jdbc, "published_at IS NULL")).isZero();
    assertThat(millis).isLessThan(60_000);
    for (var cycle : Stream.concat(cyclesA.stream(), cyclesB.stream()).toList()) {
      assertThat(cycle).isLessThanOrEqualTo(properties.batchSize());
    }
  }

  private List<Integer> run(OutboxRelay own, CountDownLatch go) {
    try {
      return drain(own, go);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
