package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PncpPropertiesTest {

  private PncpProperties bind(Map<String, String> source) {
    return new Binder(new MapConfigurationPropertySource(source))
        .bindOrCreate("radar.pncp", PncpProperties.class);
  }

  @Test
  void defaultsAreTheValuesOfTheResiliencePolicy() {
    var p = bind(Map.of());

    assertThat(p.baseUrl()).isEqualTo("https://pncp.gov.br");
    assertThat(p.pageSize()).isEqualTo(50);
    assertThat(p.requestsPerSecond()).isEqualTo(0.2);
    assertThat(p.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
    assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(p.maxConcurrentCalls()).isEqualTo(4);
    assertThat(p.bulkheadMaxWait()).isEqualTo(Duration.ofSeconds(30));
    assertThat(p.rateLimiterMaxWait()).isEqualTo(Duration.ofSeconds(120));
    assertThat(p.retry()).isEqualTo(new PncpProperties.Retry(4, Duration.ofSeconds(1), 2.0, 0.5));
    assertThat(p.circuitBreaker())
        .isEqualTo(
            new PncpProperties.CircuitBreaker(
                20, 50f, Duration.ofSeconds(60), Duration.ofSeconds(10)));
  }

  @Test
  void valuesCanBeOverridden() {
    var p =
        bind(
            Map.of(
                "radar.pncp.base-url", "http://localhost:9",
                "radar.pncp.requests-per-second", "0.5"));

    assertThat(p.baseUrl()).isEqualTo("http://localhost:9");
    assertThat(p.requestsPerSecond()).isEqualTo(0.5);
  }

  /** Mutable copy of the defaults, so each case changes one value. */
  static final class Values {
    int pageSize = 50;
    double rps = 1;
    Duration requestTimeout = Duration.ofSeconds(15);
    Duration connectTimeout = Duration.ofSeconds(5);
    int attempts = 4;
    Duration backoff = Duration.ofSeconds(1);
    double multiplier = 2;
    double jitter = 0.5;
    int window = 20;
    float failurePercent = 50;
    Duration openWait = Duration.ofSeconds(60);
    Duration slow = Duration.ofSeconds(10);
    int bulkhead = 4;
    Duration bulkheadWait = Duration.ofSeconds(30);
    Duration rateWait = Duration.ofSeconds(60);

    PncpProperties build() {
      return new PncpProperties(
          "https://pncp.gov.br",
          pageSize,
          rps,
          requestTimeout,
          connectTimeout,
          new PncpProperties.Retry(attempts, backoff, multiplier, jitter),
          new PncpProperties.CircuitBreaker(window, failurePercent, openWait, slow),
          bulkhead,
          bulkheadWait,
          rateWait);
    }
  }

  private static Arguments invalid(String name, Consumer<Values> change) {
    return Arguments.of(name, change);
  }

  static Stream<Arguments> invalidValues() {
    return Stream.of(
        invalid("pageSize", v -> v.pageSize = 9),
        invalid("pageSize", v -> v.pageSize = 51),
        invalid("requestsPerSecond", v -> v.rps = 0),
        invalid("requestTimeout", v -> v.requestTimeout = Duration.ZERO),
        invalid("connectTimeout", v -> v.connectTimeout = Duration.ofSeconds(-1)),
        invalid("maxConcurrentCalls", v -> v.bulkhead = 0),
        invalid("bulkheadMaxWait", v -> v.bulkheadWait = Duration.ofMillis(-1)),
        invalid("rateLimiterMaxWait", v -> v.rateWait = Duration.ofMillis(-1)),
        invalid("maxAttempts", v -> v.attempts = 0),
        invalid("initialBackoff", v -> v.backoff = Duration.ZERO),
        invalid("multiplier", v -> v.multiplier = 0.9),
        invalid("jitter", v -> v.jitter = -0.1),
        invalid("jitter", v -> v.jitter = 1),
        invalid("windowSize", v -> v.window = 0),
        invalid("failureRatePercent", v -> v.failurePercent = 0),
        invalid("failureRatePercent", v -> v.failurePercent = 100.5f),
        invalid("openWait", v -> v.openWait = Duration.ZERO),
        invalid("slowCallThreshold", v -> v.slow = Duration.ZERO));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidValues")
  void invalidValuesAreRejectedWithTheirName(String name, Consumer<Values> change) {
    var values = new Values();
    change.accept(values);

    assertThatThrownBy(values::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(name);
  }

  @Test
  void boundaryValuesAreAccepted() {
    var values = new Values();
    values.pageSize = 10;
    values.attempts = 1;
    values.window = 1;
    values.failurePercent = 100;
    values.jitter = 0;
    values.multiplier = 1;
    values.bulkheadWait = Duration.ZERO;
    values.rateWait = Duration.ZERO;

    assertThat(values.build().pageSize()).isEqualTo(10);
    assertThat(new Values().build().pageSize()).isEqualTo(50);
  }
}
