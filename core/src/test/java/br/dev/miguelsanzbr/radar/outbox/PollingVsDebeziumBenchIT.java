package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.TopicReader;
import br.dev.miguelsanzbr.radar.outbox.cdc.CdcInfra;
import br.dev.miguelsanzbr.radar.outbox.cdc.CdcScripts;
import br.dev.miguelsanzbr.radar.outbox.cdc.CdcSpringBootTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Polling relay against Debezium on the same machine and the same table (ADR-0018,
 * docs/benchmarks/cdc/polling-vs-debezium.md): 10 000 events appended through the real writer, at a
 * steady rates (100/s, which the relay sustains, and 250/s, which it does not) and in one burst,
 * per mode. Latency runs from {@code created_at} to the broker append time (topics are switched to
 * LogAppendTime, so the timestamp is the broker's, not the producer's). One run per mode never
 * overlaps the other: the relay loop stops before the connector starts.
 *
 * <p>./mvnw -Pbench verify -pl core -am -Dtest=NoSuch -Dit.test=PollingVsDebeziumBenchIT
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@Tag("bench")
@CdcSpringBootTest
class PollingVsDebeziumBenchIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int EVENTS = 10_000;
  private static final int WARM_UP = 500;
  private static final int STEADY_RATE = 250;
  private static final List<String> TOPICS = List.of("radar.procurement.v1", "radar.alert.v1");
  private static final String DESCRIPTION =
      "Aquisicao de material de escritorio e de informatica. ".repeat(30);

  record Payload(String id, String objectDescription, String uf, long estimatedValue) {}

  record Result(
      String mode,
      String scenario,
      int events,
      double p50Ms,
      double p95Ms,
      double maxMs,
      double insertSeconds,
      double drainAfterLastInsertSeconds,
      long walBytes,
      double postgresCpuSeconds,
      double postgresCpuAvgPercent,
      double postgresCpuPeakPercent,
      double kafkaCpuSeconds,
      double connectorCpuSeconds) {}

  @Autowired OutboxWriter writer;
  @Autowired ObjectProvider<OutboxRelay> relay;
  @Autowired OutboxCleanup cleanup;
  @Autowired OutboxProperties properties;
  @Autowired TransactionTemplate transaction;
  @Autowired JdbcTemplate jdbc;

  @Test
  void pollingAgainstDebezium() throws Exception {
    useBrokerAppendTime();
    var results = new ArrayList<Result>();

    var scheduler = new OutboxScheduler(relay, cleanup, properties);
    var loop = Executors.newSingleThreadScheduledExecutor();
    loop.scheduleWithFixedDelay(scheduler::relay, 0, 500, TimeUnit.MILLISECONDS);
    try {
      run("polling", "warm-up", WARM_UP, STEADY_RATE, false);
      results.add(run("polling", "steady-100", EVENTS, 100, true));
      results.add(run("polling", "steady-250", EVENTS, 250, true));
      results.add(run("polling", "burst", EVENTS, 0, true));
    } finally {
      loop.shutdown();
      assertThat(loop.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    // why: polling is measured without the envelope column, cdc with it: the column is opt-in
    CdcScripts.enable(jdbc);
    CdcInfra.startConnector();
    run("cdc", "warm-up", WARM_UP, STEADY_RATE, false);
    results.add(run("cdc", "steady-100", EVENTS, 100, true));
    results.add(run("cdc", "steady-250", EVENTS, 250, true));
    results.add(run("cdc", "burst", EVENTS, 0, true));

    var out = Path.of("target", "cdc-bench");
    Files.createDirectories(out);
    Files.writeString(
        out.resolve("results.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    results.forEach(r -> System.out.println("BENCH " + JSON.writeValueAsString(r)));
  }

  private static void useBrokerAppendTime() throws Exception {
    try (var admin =
        Admin.create(Map.of("bootstrap.servers", CdcInfra.KAFKA.getBootstrapServers()))) {
      var changes = new HashMap<ConfigResource, java.util.Collection<AlterConfigOp>>();
      for (var topic : TOPICS) {
        changes.put(
            new ConfigResource(ConfigResource.Type.TOPIC, topic),
            List.of(
                new AlterConfigOp(
                    new ConfigEntry("message.timestamp.type", "LogAppendTime"),
                    AlterConfigOp.OpType.SET)));
      }
      admin.incrementalAlterConfigs(changes).all().get();
    }
  }

  private Result run(String mode, String scenario, int events, int rate, boolean measured)
      throws Exception {
    var run = mode + "-" + scenario;
    var batch = rate == 0 ? 100 : 5;
    var walBefore = jdbc.queryForObject("SELECT pg_current_wal_lsn()", String.class);
    var pgCpuBefore = cpuMicros(CdcInfra.POSTGRES.getContainerId());
    var kafkaCpuBefore = cpuMicros(CdcInfra.KAFKA.getContainerId());
    var connectorCpuBefore =
        "cdc".equals(mode) ? cpuMicros(CdcInfra.startConnector().getContainerId()) : 0;
    var sampler = new DockerStatsSampler(CdcInfra.POSTGRES.getContainerId());
    sampler.start();

    var started = System.nanoTime();
    var lastInsert = started;
    var lastInsertMillis = System.currentTimeMillis();
    for (var sent = 0; sent < events; sent += batch) {
      var first = sent;
      var count = Math.min(batch, events - sent);
      var tickStart = System.nanoTime();
      transaction.executeWithoutResult(
          status ->
              IntStream.range(first, first + count)
                  .forEach(
                      i ->
                          writer.write(
                              "Procurement",
                              "bench-" + run + "-" + (i % 1000),
                              i / 1000,
                              "ProcurementUpdated",
                              Instant.now(),
                              new Payload("bench-" + run + "-" + i, DESCRIPTION, "SP", i))));
      lastInsert = System.nanoTime();
      lastInsertMillis = System.currentTimeMillis();
      if (rate > 0) {
        var due = tickStart + TimeUnit.SECONDS.toNanos(1) * count / rate;
        var wait = due - System.nanoTime();
        if (wait > 0) {
          TimeUnit.NANOSECONDS.sleep(wait);
        }
      }
    }
    var insertSeconds = (lastInsert - started) / 1e9;

    var records = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
    var topic = TOPICS.getFirst();
    records.addAll(
        TopicReader.readUntil(
            CdcInfra.KAFKA.getBootstrapServers(),
            topic,
            read ->
                read.stream().filter(r -> r.key().startsWith("bench-" + run + "-")).count()
                    >= events,
            Duration.ofMinutes(3)));
    var finished = System.nanoTime();
    sampler.stop();
    var pgCpuAfter = cpuMicros(CdcInfra.POSTGRES.getContainerId());
    var kafkaCpuAfter = cpuMicros(CdcInfra.KAFKA.getContainerId());
    var connectorCpuAfter =
        "cdc".equals(mode) ? cpuMicros(CdcInfra.startConnector().getContainerId()) : 0;
    var wal =
        jdbc.queryForObject(
            "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)::bigint",
            Long.class,
            walBefore);

    var createdAt = new HashMap<String, Double>();
    jdbc.query(
        "SELECT event_id::text, extract(epoch FROM created_at) * 1000 FROM outbox.outbox_event WHERE aggregate_id LIKE ?",
        rs -> {
          createdAt.put(rs.getString(1), rs.getDouble(2));
        },
        "bench-" + run + "-%");
    var latencies = new ArrayList<Double>();
    var lastTimestamp = 0L;
    for (var record : records) {
      if (!record.key().startsWith("bench-" + run + "-")) {
        continue;
      }
      var eventId =
          new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
      latencies.add(record.timestamp() - createdAt.get(eventId));
      lastTimestamp = Math.max(lastTimestamp, record.timestamp());
    }
    assertThat(latencies).hasSize(events);
    Collections.sort(latencies);
    var wallSeconds = (finished - started) / 1e9;
    var result =
        new Result(
            mode,
            scenario,
            events,
            percentile(latencies, 0.50),
            percentile(latencies, 0.95),
            latencies.getLast(),
            insertSeconds,
            Math.max(0, lastTimestamp - lastInsertMillis) / 1000.0,
            wal,
            (pgCpuAfter - pgCpuBefore) / 1e6,
            sampler.average(),
            sampler.peak(),
            (kafkaCpuAfter - kafkaCpuBefore) / 1e6,
            (connectorCpuAfter - connectorCpuBefore) / 1e6);
    System.out.println("BENCH-RUN " + run + " wall=" + wallSeconds + "s");
    return measured ? result : null;
  }

  private static double percentile(List<Double> sorted, double p) {
    return sorted.get((int) Math.ceil(p * sorted.size()) - 1);
  }

  /** CPU time the container's cgroup has used so far, in microseconds. */
  private static long cpuMicros(String containerId) throws IOException, InterruptedException {
    var process =
        new ProcessBuilder("docker", "exec", containerId, "cat", "/sys/fs/cgroup/cpu.stat")
            .redirectErrorStream(true)
            .start();
    var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor();
    return text.lines()
        .filter(l -> l.startsWith("usage_usec "))
        .mapToLong(l -> Long.parseLong(l.split(" ")[1]))
        .findFirst()
        .orElseThrow();
  }

  /** Samples {@code docker stats} of one container about once a second. */
  private static final class DockerStatsSampler {
    private final String container;
    private final List<Double> samples = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    DockerStatsSampler(String container) {
      this.container = container;
    }

    void start() {
      running.set(true);
      thread =
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    while (running.get()) {
                      try {
                        var process =
                            new ProcessBuilder(
                                    "docker",
                                    "stats",
                                    "--no-stream",
                                    "--format",
                                    "{{.CPUPerc}}",
                                    container)
                                .redirectErrorStream(true)
                                .start();
                        var line =
                            new String(
                                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                                .trim();
                        process.waitFor();
                        samples.add(Double.parseDouble(line.replace("%", "")));
                      } catch (IOException | NumberFormatException e) {
                        // a missed sample is not a failed run
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                      }
                    }
                  });
    }

    void stop() throws InterruptedException {
      running.set(false);
      thread.join(10_000);
    }

    double average() {
      return samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    double peak() {
      return samples.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }
  }
}
