package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;

/**
 * S1: text search, top 20, with a term of the 200 in {@code terms.csv} (real objects and organs).
 */
public class S1TextSearch extends Simulation {

  /** The scenario with {@code users} in flight; {@link SloMix} runs it next to the others. */
  static PopulationBuilder population(int users) {
    return Bench.run(
        "s1",
        users,
        phase ->
            scenario("S1" + phase)
                .feed(csv("terms.csv").random())
                .exec(
                    http("S1 text search" + phase)
                        .get("/api/v1/procurements")
                        .queryParam("q", "#{term}")
                        .queryParam("limit", "20")
                        .check(status().is(200)))
                .pause(Bench.think()));
  }

  public S1TextSearch() {
    setUp(population(Bench.users("s1", 50))).protocols(Bench.protocol());
  }
}
