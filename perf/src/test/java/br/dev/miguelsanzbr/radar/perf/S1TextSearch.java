package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;

/**
 * S1: text search, top 20, with a term of the 200 in {@code terms.csv} (real objects and organs).
 */
public class S1TextSearch extends Simulation {

  public S1TextSearch() {
    setUp(
            Bench.run(
                "s1",
                Bench.users("s1", 50),
                phase ->
                    scenario("S1" + phase)
                        .feed(csv("terms.csv").random())
                        .exec(
                            http("S1 text search" + phase)
                                .get("/api/v1/procurements")
                                .queryParam("q", "#{term}")
                                .queryParam("limit", "20")
                                .check(status().is(200)))
                        .pause(Bench.think())))
        .protocols(Bench.protocol());
  }
}
