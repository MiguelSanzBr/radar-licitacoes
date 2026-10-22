package br.dev.miguelsanzbr.radar.observability;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;

/** Reads the W3C {@code traceparent} of the span active on the calling thread. */
public class TraceparentInjector {

  public static final String HEADER = "traceparent";

  private final Tracer tracer;
  private final Propagator propagator;

  public TraceparentInjector(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /** The current {@code traceparent}, or null when no span is active (or tracing is off). */
  public String current() {
    var context = tracer.currentTraceContext().context();
    if (context == null) {
      return null;
    }
    var carrier = new HashMap<String, String>();
    propagator.inject(context, carrier, Map::put);
    return carrier.get(HEADER);
  }
}
