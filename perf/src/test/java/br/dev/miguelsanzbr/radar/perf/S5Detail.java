package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * S5: detail by id, 80% of the reads on a hot set of ids (the cache hits) and the rest spread over
 * a large cold set. The ids come from {@code -Dbench.ids-file}, one per line, hot ones first; the
 * hit ratio is read from the application's own counters after the run.
 */
public class S5Detail extends Simulation {

  private static final int HOT = 200;
  private static final double HOT_SHARE = 0.8;

  private static Iterator<Map<String, Object>> ids() {
    var file = Path.of(System.getProperty("bench.ids-file", "target/bench/ids.csv"));
    List<String> all;
    try {
      all = Files.readAllLines(file);
    } catch (IOException e) {
      throw new IllegalStateException("S5 precisa da lista de ids em " + file, e);
    }
    var hot = all.subList(0, Math.min(HOT, all.size()));
    var cold = all.subList(hot.size(), all.size());
    return Stream.generate(
            () -> {
              var random = ThreadLocalRandom.current();
              var pool = cold.isEmpty() || random.nextDouble() < HOT_SHARE ? hot : cold;
              return Map.<String, Object>of("id", pool.get(random.nextInt(pool.size())));
            })
        .iterator();
  }

  /** The scenario with {@code users} in flight; {@link SloMix} runs it next to the others. */
  static PopulationBuilder population(int users) {
    var feeder = ids();
    return Bench.run(
        "s5",
        users,
        phase ->
            scenario("S5" + phase)
                .feed(feeder)
                .exec(
                    http("S5 detail" + phase)
                        .get("/api/v1/procurements/#{id}")
                        .check(status().is(200)))
                .pause(Bench.think()));
  }

  public S5Detail() {
    setUp(population(Bench.users("s5", 100))).protocols(Bench.protocol());
  }
}
