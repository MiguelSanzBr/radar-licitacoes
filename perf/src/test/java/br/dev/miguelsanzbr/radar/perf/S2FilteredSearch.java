package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;

/** S2: text search with the state and modality filters and {@code open=true}. */
public class S2FilteredSearch extends Simulation {

  public S2FilteredSearch() {
    setUp(
            Bench.run(
                "s2",
                Bench.users("s2", 50),
                phase ->
                    scenario("S2" + phase)
                        .feed(csv("terms.csv").random())
                        .feed(csv("filters.csv").random())
                        .exec(
                            http("S2 filtered search" + phase)
                                .get("/api/v1/procurements")
                                .queryParam("q", "#{term}")
                                .queryParam("uf", "#{uf}")
                                .queryParam("modality", "#{modality}")
                                .queryParam("open", "true")
                                .queryParam("limit", "20")
                                .check(status().is(200)))
                        .pause(Bench.think())))
        .protocols(Bench.protocol());
  }
}
