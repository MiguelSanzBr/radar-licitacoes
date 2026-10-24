package br.dev.miguelsanzbr.radar.e2e;

import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.BACKFILL;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.ITEMS_IN_FIXTURE;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.REPO;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.TAG;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.await;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.get;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.getResponse;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.operatorToken;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.post;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.run;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.sql;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

/**
 * The compose with ingestor and search-api instead of the monolith (profile split, ADR-0028), from
 * the images of {@code make image-split}: the ingestor collects the fixture day, its relay
 * publishes, and the search-api projects the events and serves them. Same fixed ports as the
 * monolith test (8080 is the search-api, 8082 the ingestor): stop the development environment (make
 * down) first.
 */
@Tag("e2e")
class SplitStackE2EIT {

  private static final String INGESTOR = "http://localhost:8082";
  private static final String SEARCH_API = "http://localhost:8080";

  private static ComposeContainer compose;

  @BeforeAll
  static void startStack() throws Exception {
    run(REPO.resolve("scripts/compose-init.sh").toString());
    for (var service : new String[] {"ingestor", "search-api"}) {
      var image = "radar-licitacoes-" + service + ":" + TAG;
      assertThat(run("docker", "image", "inspect", image))
          .as("imagem %s ausente: rode `make image-split`", image)
          .isZero();
    }
    compose =
        new ComposeContainer(
                "radar-e2e-split", new File(REPO.toFile(), "infra/compose/docker-compose.yml"))
            .withEnv("RADAR_TAG", TAG)
            // naming the two services starts them (and what they depend on) and not the monolith;
            // withScaledService("app", 0) would instead start only app and its dependencies
            .withServices("ingestor", "search-api")
            .withPull(false)
            .withBuild(false)
            .withRemoveVolumes(true)
            .withStartupTimeout(Duration.ofMinutes(10));
    compose.start();
  }

  @AfterAll
  static void stopStack() {
    if (compose != null) {
      compose.stop();
    }
  }

  @Test
  void aDayCollectedByTheIngestorIsServedByTheSearchApi() throws Exception {
    await(Duration.ofMinutes(3), () -> get(INGESTOR + "/actuator/health/readiness"));
    await(Duration.ofMinutes(3), () -> get(SEARCH_API + "/actuator/health/readiness"));
    assertThat(compose.getContainerByServiceName("app"))
        .as("the monolith is not running")
        .isEmpty();

    var token = operatorToken();
    assertThat(post(SEARCH_API + BACKFILL, token))
        .as("the backfill belongs to the ingestor, not to the search-api")
        .isGreaterThanOrEqualTo(400);
    assertThat(post(INGESTOR + BACKFILL, token)).isEqualTo(202);

    await(
        Duration.ofMinutes(3),
        () ->
            sql(compose, "select count(*) from ingest.procurement").equals("" + ITEMS_IN_FIXTURE));
    await(
        Duration.ofMinutes(2),
        () ->
            sql(compose, "select count(*) from outbox.outbox_event where published_at is null")
                    .equals("0")
                && !sql(compose, "select count(*) from outbox.outbox_event").equals("0"));
    // the search-api projects what the ingestor's relay published
    await(
        Duration.ofMinutes(4),
        () ->
            sql(compose, "select count(*) from search.procurement_search_view")
                .equals("" + ITEMS_IN_FIXTURE));

    var page = getResponse(SEARCH_API + "/api/v1/procurements?limit=5");
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("\"items\":[{");
    assertThat(getResponse(INGESTOR + "/api/v1/procurements").statusCode())
        .as("the ingestor has no search API")
        .isEqualTo(404);
  }
}
