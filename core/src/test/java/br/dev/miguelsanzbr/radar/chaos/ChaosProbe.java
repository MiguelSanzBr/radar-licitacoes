package br.dev.miguelsanzbr.radar.chaos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Looks at the data through the proxy that never gets a fault, and samples the backlog four times a
 * second. A sample that fails (Postgres is down) is skipped, so the last sample of the list tells
 * how recent the knowledge is.
 */
final class ChaosProbe implements AutoCloseable {

  /** The pipeline at one instant: millis are since the test started. */
  record Sample(
      long atMillis,
      long ingested,
      long appended,
      long pending,
      long projected,
      long oldestPendingSeconds) {

    /** Events that exist in the outbox and are not in the read side yet. */
    long unprojected() {
      return appended - projected;
    }
  }

  private static final String SAMPLE =
      """
      SELECT (SELECT count(*) FROM ingest.procurement),
             (SELECT count(*) FROM outbox.outbox_event),
             (SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL),
             (SELECT count(*) FROM search.processed_event WHERE consumer = 'search-projector'),
             (SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)::bigint
                FROM outbox.outbox_event WHERE published_at IS NULL)
      """;

  private final long start = System.nanoTime();
  private final long startEpoch = System.currentTimeMillis();
  private final List<Sample> samples = new ArrayList<>();
  private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
  private Connection connection;

  void startSampling() {
    sampler.scheduleWithFixedDelay(this::sampleOnce, 0, 250, TimeUnit.MILLISECONDS);
  }

  long nowMillis() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /** The wall clock time of an instant measured by {@link #nowMillis()}. */
  long epochMillis(long testMillis) {
    return startEpoch + testMillis;
  }

  private void sampleOnce() {
    try {
      var sample = query(SAMPLE);
      synchronized (samples) {
        samples.add(new Sample(nowMillis(), sample[0], sample[1], sample[2], sample[3], sample[4]));
      }
    } catch (SQLException e) {
      // Postgres is down or restarting: no sample, the next tick tries again
    }
  }

  /** The most recent sample, or null before the first one. */
  Sample latest() {
    synchronized (samples) {
      return samples.isEmpty() ? null : samples.getLast();
    }
  }

  /** Worst values between two instants. */
  Sample peak(long fromMillis, long toMillis) {
    long ingested = 0;
    long appended = 0;
    long pending = 0;
    long projected = 0;
    long oldest = 0;
    synchronized (samples) {
      for (var s : samples) {
        if (s.atMillis() >= fromMillis && s.atMillis() <= toMillis) {
          ingested = Math.max(ingested, s.ingested());
          appended = Math.max(appended, s.appended());
          pending = Math.max(pending, s.pending());
          projected = Math.max(projected, s.projected());
          oldest = Math.max(oldest, s.oldestPendingSeconds());
        }
      }
    }
    return new Sample(toMillis, ingested, appended, pending, projected, oldest);
  }

  /** Largest {@code unprojected} between two instants. */
  long peakUnprojected(long fromMillis, long toMillis) {
    synchronized (samples) {
      return samples.stream()
          .filter(s -> s.atMillis() >= fromMillis && s.atMillis() <= toMillis)
          .mapToLong(Sample::unprojected)
          .max()
          .orElse(0);
    }
  }

  synchronized long scalar(String sql) throws SQLException {
    return query(sql)[0];
  }

  /** The row of a query that returns one row of numbers. */
  private synchronized long[] query(String sql) throws SQLException {
    try {
      if (connection == null || connection.isClosed()) {
        connection =
            DriverManager.getConnection(
                ChaosEnvironment.observerJdbcUrl() + "?connectTimeout=3&socketTimeout=10",
                ChaosEnvironment.USER,
                ChaosEnvironment.PASSWORD);
      }
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery(sql)) {
        rows.next();
        var columns = rows.getMetaData().getColumnCount();
        var row = new long[columns];
        for (var i = 0; i < columns; i++) {
          row[i] = rows.getLong(i + 1);
        }
        return row;
      }
    } catch (SQLException e) {
      closeQuietly();
      throw e;
    }
  }

  private void closeQuietly() {
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (SQLException e) {
      // already broken
    }
    connection = null;
  }

  @Override
  public synchronized void close() {
    sampler.shutdownNow();
    closeQuietly();
  }
}
