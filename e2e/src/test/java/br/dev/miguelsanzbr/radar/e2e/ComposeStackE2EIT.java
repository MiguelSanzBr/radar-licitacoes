package br.dev.miguelsanzbr.radar.e2e;

import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.BACKFILL;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.ITEMS_IN_FIXTURE;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.REPO;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.TAG;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.await;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.get;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.operatorToken;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.post;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.query;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.run;
import static br.dev.miguelsanzbr.radar.e2e.E2eSupport.sql;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

/**
 * O compose inteiro (infra/compose) com a imagem {@code radar-licitacoes:local} (make image): sobe,
 * carrega o dia da fixture pelo backfill do operador e confere o resultado no banco, no outbox e no
 * Prometheus do otel-lgtm. Usa as portas fixas do compose (8080, 8180, 3000): pare o ambiente de
 * desenvolvimento (make down) antes.
 *
 * <p>Quando a busca e os alertas existirem, trocar a conferência por SQL por uma busca na API (GET
 * /api/v1/procurements) e acrescentar o alerta que chega ao Mailpit (porta 8025).
 */
@Tag("e2e")
class ComposeStackE2EIT {

  private static final String IMAGE = "radar-licitacoes:" + TAG;
  private static final String APP = "http://localhost:8080";

  private static ComposeContainer compose;

  @BeforeAll
  static void startStack() throws Exception {
    run(REPO.resolve("scripts/compose-init.sh").toString());
    assertThat(run("docker", "image", "inspect", IMAGE))
        .as("imagem %s ausente: rode `make image`", IMAGE)
        .isZero();
    compose =
        new ComposeContainer(
                "radar-e2e", new File(REPO.toFile(), "infra/compose/docker-compose.yml"))
            .withEnv("RADAR_TAG", TAG)
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
  void backfillOfTheFixtureDayReachesDatabaseKafkaAndMetrics() throws Exception {
    await(Duration.ofMinutes(3), () -> get(APP + "/actuator/health/readiness"));

    assertThat(post(APP + BACKFILL, null)).as("sem token o /ops responde 401").isEqualTo(401);

    var token = operatorToken();
    assertThat(post(APP + BACKFILL, token)).isEqualTo(202);

    await(
        Duration.ofMinutes(3),
        () ->
            sql(compose, "select count(*) from ingest.procurement").equals("" + ITEMS_IN_FIXTURE));
    await(
        Duration.ofMinutes(1),
        () ->
            sql(compose, "select status from ingest.ingestion_run where kind = 'BACKFILL'")
                .equals("DONE"));
    await(
        Duration.ofMinutes(2),
        () ->
            sql(compose, "select count(*) from outbox.outbox_event where published_at is null")
                    .equals("0")
                && !sql(compose, "select count(*) from outbox.outbox_event").equals("0"));

    // the read-side role was created by V6 with the secret file's password, over TCP (scram)
    var readWriterPassword =
        Files.readString(REPO.resolve("infra/compose/secrets/read_writer_password")).trim();
    var login =
        compose
            .getContainerByServiceName("postgres")
            .orElseThrow()
            .execInContainer(
                "env",
                "PGPASSWORD=" + readWriterPassword,
                "psql",
                "-h",
                "127.0.0.1",
                "-U",
                "radar_read_writer",
                "-d",
                "radar",
                "-tA",
                "-c",
                "select current_user");
    assertThat(login.getStdout().trim()).isEqualTo("radar_read_writer");

    // metrics are pushed over OTLP to otel-lgtm and read back through Grafana's Prometheus
    await(
        Duration.ofMinutes(3),
        () ->
            query("ingest_items_total").contains("\"discovered\"")
                && query("pncp_items_fetched_total").contains("\"publicacao\""));
  }
}
