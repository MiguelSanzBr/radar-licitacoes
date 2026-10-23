package br.dev.miguelsanzbr.radar.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How many pages the collection reads ahead of the one being processed. Two by default: each page
 * in flight holds a rate limiter permit queue slot (5 s each at 0.2 req/s), so a deep read ahead
 * would only fill the limiter's wait queue.
 */
@ConfigurationProperties("radar.ingest")
public record IngestFetchProperties(@DefaultValue("2") int fetchAhead) {

  public IngestFetchProperties {
    if (fetchAhead < 1) {
      throw new IllegalArgumentException("fetchAhead must be >= 1");
    }
  }

  /** Never deeper than the bulkhead: further pages would only wait behind it. */
  static int effective(int fetchAhead, int maxConcurrentCalls) {
    return Math.min(fetchAhead, maxConcurrentCalls);
  }
}
