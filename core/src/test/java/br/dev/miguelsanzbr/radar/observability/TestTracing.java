package br.dev.miguelsanzbr.radar.observability;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/** A real OpenTelemetry tracer behind the Micrometer API, with W3C propagation and a span sink. */
final class TestTracing {

  final InMemorySpanExporter spans = InMemorySpanExporter.create();
  final Tracer tracer;
  final Propagator propagator;

  TestTracing() {
    var sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    var otel = sdk.getTracer("test");
    this.tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> {});
    this.propagator = new OtelPropagator(sdk.getPropagators(), otel);
  }
}
