package br.dev.miguelsanzbr.radar.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

/** Thread-safe publisher that records what it was given and can fail for chosen aggregates. */
public final class InMemoryEventPublisher implements EventPublisher {

  private final List<OutboxMessage> published = new CopyOnWriteArrayList<>();
  private final Map<String, Integer> failuresLeft = new ConcurrentHashMap<>();
  private final List<String> attemptedAggregates = new CopyOnWriteArrayList<>();

  private volatile long latencyNanos;

  /** Every publish takes this long, like a broker round trip, so concurrent relays overlap. */
  public void latency(Duration latency) {
    latencyNanos = latency.toNanos();
  }

  /** The next {@code times} publishes of this aggregate id throw a {@link PublishException}. */
  public void failFor(String aggregateId, int times) {
    failuresLeft.put(aggregateId, times);
  }

  @Override
  public void publish(OutboxMessage message) throws PublishException {
    attemptedAggregates.add(message.aggregateId());
    LockSupport.parkNanos(latencyNanos);
    var left = failuresLeft.merge(message.aggregateId(), -1, Integer::sum);
    if (left >= 0) {
      throw new PublishException("broker unavailable for " + message.aggregateId());
    }
    published.add(message);
  }

  public List<OutboxMessage> published() {
    return new ArrayList<>(published);
  }

  public List<Long> publishedIds() {
    return published().stream().map(OutboxMessage::id).toList();
  }

  public int attempts() {
    return attemptedAggregates.size();
  }

  public void reset() {
    published.clear();
    failuresLeft.clear();
    attemptedAggregates.clear();
  }
}
