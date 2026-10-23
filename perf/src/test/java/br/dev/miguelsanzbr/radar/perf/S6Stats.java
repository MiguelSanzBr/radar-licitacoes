package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;

/** S6: the statistics by state and by month, read from the incremental stats table. */
public class S6Stats extends Simulation {

  public S6Stats() {
    setUp(
            Bench.run(
                "s6",
                Bench.users("s6", 10),
                phase ->
                    scenario("S6" + phase)
                        .exec(
                            http("S6 stats by uf" + phase)
                                .get("/api/v1/stats")
                                .queryParam("groupBy", "uf")
                                .check(status().is(200)))
                        .pause(Bench.think())
                        .exec(
                            http("S6 stats by month" + phase)
                                .get("/api/v1/stats")
                                .queryParam("groupBy", "month")
                                .check(status().is(200)))
                        .pause(Bench.think())))
        .protocols(Bench.protocol());
  }
}
