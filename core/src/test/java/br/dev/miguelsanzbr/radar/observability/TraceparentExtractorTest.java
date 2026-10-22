package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

class TraceparentExtractorTest {

  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String SPAN_ID = "b7ad6b7169203331";

  private final TestTracing tracing = new TestTracing();
  private final TraceparentExtractor extractor =
      new TraceparentExtractor(tracing.tracer, tracing.propagator);

  @Test
  void aNullTraceparentOpensNothing() {
    try (var ignored = extractor.continueFrom(null, "work")) {
      assertThat(tracing.tracer.currentSpan()).isNull();
    }

    assertThat(tracing.spans.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void aValidTraceparentOpensAChildSpanThatIsCurrentUntilClosed() {
    try (var ignored = extractor.continueFrom("00-" + TRACE_ID + "-" + SPAN_ID + "-01", "work")) {
      var current = tracing.tracer.currentSpan();

      assertThat(current).isNotNull();
      assertThat(current.context().traceId()).isEqualTo(TRACE_ID);
      assertThat(current.context().parentId()).isEqualTo(SPAN_ID);
    }

    assertThat(tracing.tracer.currentSpan()).isNull();
    assertThat(tracing.spans.getFinishedSpanItems())
        .singleElement()
        .satisfies(
            span -> {
              assertThat(span.getName()).isEqualTo("work");
              assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
              assertThat(span.getParentSpanId()).isEqualTo(SPAN_ID);
            });
  }

  @Test
  void theNoopTracerAcceptsAnyTraceparent() {
    var noop = new TraceparentExtractor(Tracer.NOOP, Propagator.NOOP);

    var scope = noop.continueFrom("00-" + TRACE_ID + "-" + SPAN_ID + "-01", "work");

    assertThatCode(scope::close).doesNotThrowAnyException();
  }
}
