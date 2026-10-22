package br.dev.miguelsanzbr.radar.observability;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Continues a trace from a stored {@code traceparent}: for work that runs later and elsewhere than
 * the request that started it (the outbox relay, consumers that do not use listener observation).
 */
public class TraceparentExtractor {

  /** Closing ends the span and restores the previous one; never throws. */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  private static final Scope NONE = () -> {};

  private final Tracer tracer;
  private final Propagator propagator;

  public TraceparentExtractor(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /**
   * Starts a span named {@code name} as a child of {@code traceparent} and makes it current until
   * the scope closes, so spans started meanwhile (a Kafka send) belong to the original trace. A
   * null {@code traceparent} opens nothing.
   */
  public Scope continueFrom(String traceparent, String name) {
    if (traceparent == null) {
      return NONE;
    }
    var span =
        propagator
            .extract(
                traceparent,
                (carrier, key) -> TraceparentInjector.HEADER.equals(key) ? carrier : null)
            .name(name)
            .start();
    var scope = tracer.withSpan(span);
    return () -> {
      scope.close();
      span.end();
    };
  }
}
