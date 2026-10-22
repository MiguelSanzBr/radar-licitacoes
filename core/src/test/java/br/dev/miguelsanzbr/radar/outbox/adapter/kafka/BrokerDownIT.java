package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.outbox.OutboxProperties;
import br.dev.miguelsanzbr.radar.outbox.OutboxRelay;
import br.dev.miguelsanzbr.radar.outbox.OutboxRows;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The broker is paused, not stopped, so the same container comes back on the same port. The outage
 * length is {@code -Dradar.test.broker-outage-seconds}, 60 by default.
 */
@KafkaSpringBootTest
class BrokerDownIT {

  private static final int EVENTS = 100;
  private static final int AGGREGATES = 5;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired OutboxRelay relay;
  @Autowired OutboxProperties properties;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
  }

  private long scalar(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private void pause() {
    var docker = SharedKafka.INSTANCE.getDockerClient();
    docker.pauseContainerCmd(SharedKafka.INSTANCE.getContainerId()).exec();
  }

  private void unpause() {
    var docker = SharedKafka.INSTANCE.getDockerClient();
    docker.unpauseContainerCmd(SharedKafka.INSTANCE.getContainerId()).exec();
  }

  @Test
  void pendingEventsSurviveABrokerOutageWithoutLoss() throws Exception {
    var outage = Duration.ofSeconds(Long.getLong("radar.test.broker-outage-seconds", 60));
    var warm = OutboxRows.insertWithHeaders(jdbc, "WARM", "ProcurementDiscovered", "{}");
    assertThat(relay.relayOnce()).isEqualTo(1);

    var loop = Executors.newSingleThreadScheduledExecutor();
    var paused = new AtomicBoolean();
    var ids = new HashSet<String>();
    long publishedDuringOutage;
    long maxAttempts;
    long erroredDuringOutage;
    long resumedAt;
    long drainMillis;
    try {
      pause();
      paused.set(true);
      for (var i = 0; i < EVENTS; i++) {
        ids.add(
            OutboxRows.insertWithHeaders(
                    jdbc,
                    "DOWN-" + (i % AGGREGATES),
                    "ProcurementUpdated",
                    "{\"aggregateVersion\":" + (i / AGGREGATES + 1) + "}")
                .toString());
      }
      loop.scheduleWithFixedDelay(
          () -> {
            try {
              relay.relayOnce();
            } catch (RuntimeException e) {
              // the next tick tries again
            }
          },
          0,
          500,
          TimeUnit.MILLISECONDS);

      // why: sample all through the outage and keep the worst value, so one bad moment cannot hide,
      // and do not end the outage before the relay has recorded at least one error
      var deadline = System.nanoTime() + outage.toNanos();
      var errorsDeadline = deadline + Duration.ofSeconds(60).toNanos();
      publishedDuringOutage = 0;
      maxAttempts = 0;
      erroredDuringOutage = 0;
      while (System.nanoTime() < deadline
          || (erroredDuringOutage == 0 && System.nanoTime() < errorsDeadline)) {
        publishedDuringOutage =
            Math.max(
                publishedDuringOutage,
                scalar(
                    "SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NOT NULL AND aggregate_id <> 'WARM'"));
        maxAttempts =
            Math.max(maxAttempts, scalar("SELECT max(attempts) FROM outbox.outbox_event"));
        erroredDuringOutage =
            scalar("SELECT count(*) FROM outbox.outbox_event WHERE last_error IS NOT NULL");
        Thread.sleep(250);
      }

      resumedAt = System.nanoTime();
      unpause();
      paused.set(false);
      await()
          .atMost(Duration.ofMinutes(3))
          .pollInterval(Duration.ofMillis(250))
          .until(() -> OutboxRows.count(jdbc, "published_at IS NULL") == 0);
      drainMillis = Duration.ofNanos(System.nanoTime() - resumedAt).toMillis();
    } finally {
      loop.shutdownNow();
      if (paused.get()) {
        unpause();
      }
    }

    assertThat(publishedDuringOutage).isZero();
    assertThat(erroredDuringOutage).isPositive();
    // why: a broker outage is not the message's fault, so it never counts towards the poison limit
    assertThat(maxAttempts)
        .as(
            "an outage must not count attempts, but these rows did: %s",
            jdbc.queryForList(
                "SELECT aggregate_id, attempts, left(last_error, 300) FROM outbox.outbox_event WHERE attempts > 0"))
        .isZero();

    var read =
        TopicReader.readUntil(
            "radar.procurement.v1",
            all -> distinctOurs(all, ids).size() == EVENTS,
            Duration.ofSeconds(60));
    var ours =
        read.stream()
            .filter(r -> ids.contains(new String(r.headers().lastHeader("eventId").value())))
            .toList();
    var distinct = distinctOurs(read, ids);
    System.out.printf(
        "BROKER_DOWN outageSeconds=%d events=%d publishedDuringOutage=%d maxAttempts=%d"
            + " erroredDuringOutage=%d delivered=%d distinct=%d duplicates=%d drainMs=%d warm=%s%n",
        outage.toSeconds(),
        EVENTS,
        publishedDuringOutage,
        maxAttempts,
        erroredDuringOutage,
        ours.size(),
        distinct.size(),
        ours.size() - distinct.size(),
        drainMillis,
        warm);

    assertThat(distinct).isEqualTo(ids);
    var lastVersion = new HashMap<String, Integer>();
    for (var record : ours) {
      var version = JSON.readTree(record.value()).get("aggregateVersion").asInt();
      var previous = lastVersion.put(record.key(), version);
      assertThat(version)
          .as("version of %s after %s", record.key(), previous)
          .isGreaterThanOrEqualTo(previous == null ? 0 : previous);
    }
  }

  private static Set<String> distinctOurs(
      List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records,
      Set<String> ids) {
    var found = new HashSet<String>();
    for (var record : records) {
      var id = new String(record.headers().lastHeader("eventId").value());
      if (ids.contains(id)) {
        found.add(id);
      }
    }
    return found;
  }
}
