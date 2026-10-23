package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;

/** S4: organ name typed with a typo (two letters swapped), found by {@code pg_trgm}. */
public class S4OrganTypo extends Simulation {

  public S4OrganTypo() {
    setUp(
            Bench.run(
                "s4",
                Bench.users("s4", 20),
                phase ->
                    scenario("S4" + phase)
                        .feed(csv("organ-typos.csv").random())
                        .exec(
                            http("S4 organ typo" + phase)
                                .get("/api/v1/organs")
                                .queryParam("q", "#{typo}")
                                .check(status().is(200)))
                        .pause(Bench.think())))
        .protocols(Bench.protocol());
  }
}
