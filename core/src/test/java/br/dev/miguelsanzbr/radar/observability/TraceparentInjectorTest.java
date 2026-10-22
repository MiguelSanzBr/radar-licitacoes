package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

class TraceparentInjectorTest {

  private final TestTracing tracing = new TestTracing();
  private final TraceparentInjector injector =
      new TraceparentInjector(tracing.tracer, tracing.propagator);

  @Test
  void withoutAnActiveSpanThereIsNoTraceparent() {
    assertThat(injector.current()).isNull();
  }

  @Test
  void anActiveSpanGivesItsW3cTraceparent() {
    var span = tracing.tracer.nextSpan().start();
    try (var ignored = tracing.tracer.withSpan(span)) {
      var traceparent = injector.current();

      assertThat(traceparent)
          .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[0-3]")
          .contains("-" + span.context().traceId() + "-" + span.context().spanId() + "-");
    } finally {
      span.end();
    }
  }

  @Test
  void theNoopTracerGivesNoTraceparent() {
    assertThat(new TraceparentInjector(Tracer.NOOP, Propagator.NOOP).current()).isNull();
  }
}
