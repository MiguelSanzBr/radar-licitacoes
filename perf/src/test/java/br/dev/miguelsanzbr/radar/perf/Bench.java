package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.function.Function;

/**
 * What the six simulations share: the target, the load knobs and the two phases of a run. Every
 * knob is a system property, so {@code scripts/bench.sh} can step the load down when the host
 * cannot sustain it without touching the code.
 *
 * <p>Each scenario runs twice in a row with the same shape: a warm-up whose requests carry the
 * suffix {@code (warmup)} in their name, and the measured phase. {@code scripts/render-metrics.sh}
 * reads only the requests without the suffix, so the warm-up is discarded.
 */
final class Bench {

  static final String WARMUP = " (warmup)";

  private Bench() {}

  static String baseUrl() {
    return System.getProperty("bench.base-url", "http://localhost:8080");
  }

  /** Users of one scenario: the plan's number, unless {@code -Dbench.users.<id>} says otherwise. */
  static int users(String id, int planned) {
    return Integer.getInteger("bench.users." + id, planned);
  }

  /** Pause after each request: a user reads the answer before the next click. */
  static Duration think() {
    return Duration.ofMillis(Long.getLong("bench.think-ms", 1_000));
  }

  static HttpProtocolBuilder protocol() {
    return http.baseUrl(baseUrl()).acceptHeader("application/json").shareConnections();
  }

  /**
   * The closed workload of the plan: {@code users} always in flight, one minute of warm-up and
   * three of measurement by default.
   *
   * @param scenario builds the scenario; its argument is the suffix of every request name
   */
  static PopulationBuilder run(String id, int users, Function<String, ScenarioBuilder> scenario) {
    var warm = Duration.ofSeconds(Long.getLong("bench.warmup-seconds", 60));
    var measured = Duration.ofSeconds(Long.getLong("bench.measured-seconds", 180));
    return scenario
        .apply(WARMUP)
        .injectClosed(constantConcurrentUsers(users).during(warm))
        .andThen(scenario.apply("").injectClosed(constantConcurrentUsers(users).during(measured)));
  }
}
