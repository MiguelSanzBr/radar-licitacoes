package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.dev.miguelsanzbr.radar.shared.UuidV7;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObservabilityConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ObservabilityConfiguration.class)
          .withBean(UuidV7.class, () -> new UuidV7(Clock.systemUTC(), new SecureRandom()));

  @Test
  void withoutATracerOrPropagatorTheBeansFallBackToTheNoopOnes() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(TraceparentInjector.class).current()).isNull();
          var scope =
              context
                  .getBean(TraceparentExtractor.class)
                  .continueFrom("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "w");
          assertThatCode(scope::close).doesNotThrowAnyException();
        });
  }

  @Test
  void aTracerAndPropagatorInTheContextAreUsed() {
    var tracing = new TestTracing();
    runner
        .withBean(Tracer.class, () -> tracing.tracer)
        .withBean(Propagator.class, () -> tracing.propagator)
        .run(
            context -> {
              var span = tracing.tracer.nextSpan().start();
              try (var ignored = tracing.tracer.withSpan(span)) {
                assertThat(context.getBean(TraceparentInjector.class).current())
                    .contains(span.context().traceId());
              } finally {
                span.end();
              }
            });
  }
}
