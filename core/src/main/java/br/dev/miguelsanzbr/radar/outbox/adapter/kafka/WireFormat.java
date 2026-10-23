package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import com.microsoft.kiota.ApiException;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.avro.AvroRuntimeException;

/**
 * What the producer and the consumers agree on for the wire. The value of a record is the JSON
 * envelope or the Avro encoding of it; inside the app it is always JSON text, so the listeners, the
 * dead-letter topics and the reprocess keep working on text. Avro is Apicurio's, in the layout the
 * Confluent serdes read too: a zero magic byte, a 4-byte schema id (the content id of the
 * registry), then the Avro binary.
 */
final class WireFormat {

  /** {@code radar.messaging.format}: {@code json} (the default) or {@code avro}. */
  static final String FORMAT = "radar.messaging.format";

  /**
   * Set by the producer on an Avro record; the consumer falls back to the magic byte without it.
   */
  static final String CONTENT_TYPE = "content-type";

  static final String AVRO = "application/avro";

  /** First byte of the Confluent framing; a JSON text never starts with it. */
  static final byte MAGIC = 0;

  /** {@code radar.messaging.registry-timeout-ms}: the longest a call to the registry may take. */
  static final String REGISTRY_TIMEOUT_MS = "radar.messaging.registry-timeout-ms";

  /**
   * {@code radar.messaging.registry-backoff-ms}: after a registry failure the consumer marks
   * records without asking it.
   */
  static final String REGISTRY_BACKOFF_MS = "radar.messaging.registry-backoff-ms";

  /** Prefix of the text a consumer gets for an Avro record it could never decode. */
  static final String UNDECODABLE = "avro-undecodable:";

  private static final Map<String, Object> DEFAULTS =
      Map.of(
          // why: 4-byte id plus content id is the Confluent layout, so a Confluent consumer reads
          // it
          "apicurio.registry.id-handler", "io.apicurio.registry.serde.Default4ByteIdHandler",
          "apicurio.registry.use-id", "contentId",
          // why: one group per topic, one artifact per event type (BACKWARD is set on the group)
          "apicurio.registry.artifact-resolver-strategy",
              TopicGroupRecordIdStrategy.class.getName(),
          "apicurio.registry.auto-register", "true",
          // why: the JDK client avoids Vert.x and its Netty 4.1
          "apicurio.registry.http.adapter", "JDK",
          // why: bounded retries of the client; the call as a whole is bounded by
          // REGISTRY_TIMEOUT_MS
          "apicurio.registry.retry-count", "2",
          "apicurio.registry.retry-backoff-ms", "200",
          // why: without it the serde registers every nested record as an artifact of its own and
          // the event schema only references them; with it each event is one self-contained schema
          "apicurio.registry.dereference-schema", "true");

  // why: the JDK client of the registry has no read timeout of its own and its settings are not
  // exposed as properties, so each call runs on a virtual thread and the caller waits a bounded
  // time
  private static final ExecutorService REGISTRY_CALLS = Executors.newVirtualThreadPerTaskExecutor();

  private WireFormat() {}

  /** The bound on one call to the registry; ten seconds unless configured. */
  static Duration registryTimeout(Map<String, ?> configs) {
    return Duration.ofMillis(
        Long.parseLong(
            String.valueOf(Objects.requireNonNullElse(configs.get(REGISTRY_TIMEOUT_MS), "10000"))));
  }

  /**
   * How long after a registry failure the consumer marks Avro records without calling it; five
   * seconds by default.
   */
  static Duration registryBackoff(Map<String, ?> configs) {
    return Duration.ofMillis(
        Long.parseLong(
            String.valueOf(Objects.requireNonNullElse(configs.get(REGISTRY_BACKOFF_MS), "5000"))));
  }

  /**
   * Runs a call to the registry and waits at most {@code timeout}. A failure keeps its cause chain
   * (so {@link #transientFailure} can read it) and a call that does not answer ends as a {@link
   * TimeoutException} in the chain.
   */
  static <T> T within(Duration timeout, Supplier<T> call) {
    var future = REGISTRY_CALLS.submit(call::get);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw new IllegalStateException(
          "registry call failed: " + e.getCause().getMessage(), e.getCause());
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException("registry call took more than " + timeout, e);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while calling the registry", e);
    }
  }

  /**
   * True when the registry may answer later: a network failure (connection refused or reset, no
   * route, a timeout, an interruption) or an answer of 408, 429 or 5xx anywhere in the cause chain.
   * A rule violation, an unknown schema id, and bytes Avro cannot read (an {@link EOFException} or
   * an {@link AvroRuntimeException}, however deep) are permanent, even though the first is an
   * {@link IOException} too.
   */
  static boolean transientFailure(Throwable failure) {
    var network = false;
    for (var t = failure; t != null; t = t.getCause()) {
      if (t instanceof EOFException || t instanceof AvroRuntimeException) {
        return false;
      }
      network |= networkShaped(t);
    }
    return network;
  }

  // why: the JDK client reports a dropped connection or a bad response as a plain IOException, so
  // that exact class counts; its subclasses count only when they are network ones
  private static boolean networkShaped(Throwable t) {
    return t.getClass() == IOException.class
        || t instanceof SocketException
        || t instanceof SocketTimeoutException
        || t instanceof UnknownHostException
        || t instanceof HttpTimeoutException
        || t instanceof TimeoutException
        || t instanceof InterruptedException
        || t instanceof ApiException api && retryableStatus(api.getResponseStatusCode());
  }

  private static boolean retryableStatus(int status) {
    return status == 408 || status == 429 || status >= 500;
  }

  /** The defaults under what the app configured, for the Apicurio serdes. */
  static Map<String, Object> registryConfig(Map<String, ?> configs) {
    var merged = new HashMap<String, Object>(DEFAULTS);
    merged.putAll(configs);
    return merged;
  }
}
