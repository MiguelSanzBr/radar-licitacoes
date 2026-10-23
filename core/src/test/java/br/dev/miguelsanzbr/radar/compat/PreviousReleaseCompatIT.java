package br.dev.miguelsanzbr.radar.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The release before this one against the schema this one expands to (ADR-0010, expand-contract):
 * Postgres gets every current migration, V14 included, and then the image of the previous release
 * starts on it. It must start (Flyway lets an applied migration it does not know pass), ingest a
 * page from the PNCP fixture and leave the cents filled by the trigger, which that release has
 * never heard of. The image is {@code radar-licitacoes:prev}, built from the commit before the
 * expand by {@code make image RADAR_TAG=prev} (docs/benchmarks/db/expand-contract.md); run it with
 * {@code make compat}.
 */
@Tag("compat")
class PreviousReleaseCompatIT {

  private static final String PREVIOUS_IMAGE =
      System.getenv().getOrDefault("RADAR_PREV_IMAGE", "radar-licitacoes:prev");

  // the same WireMock image the compose stack uses as the PNCP
  private static final String WIREMOCK =
      "wiremock/wiremock:3.13.2-alpine@sha256:f8c42a38dca3f4a1d7219af11c80438740f39eebb1505b0f029aed743c20e147";

  private static final Network NETWORK = Network.newNetwork();

  private static final PostgreSQLContainer DB =
      new PostgreSQLContainer("postgres:18").withNetwork(NETWORK).withNetworkAliases("db");

  private static final GenericContainer<?> PNCP =
      new GenericContainer<>(WIREMOCK)
          .withNetwork(NETWORK)
          .withNetworkAliases("pncp")
          .withExposedPorts(8080)
          .withEnv("JAVA_OPTS", "-Xms32m -Xmx128m")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("compat/pncp-mappings.json"),
              "/home/wiremock/mappings/pncp.json")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("compat/pncp-page-v1.json"),
              "/home/wiremock/__files/pncp-page-v1.json")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("compat/pncp-page-v2.json"),
              "/home/wiremock/__files/pncp-page-v2.json")
          .withCommand("--disable-banner")
          .waitingFor(Wait.forHttp("/__admin/health").forPort(8080).forStatusCode(200));

  private static final GenericContainer<?> APP =
      new GenericContainer<>(PREVIOUS_IMAGE)
          .withNetwork(NETWORK)
          .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/" + DB.getDatabaseName())
          .withEnv("SPRING_DATASOURCE_USERNAME", DB.getUsername())
          .withEnv("SPRING_DATASOURCE_PASSWORD", DB.getPassword())
          .withEnv("RADAR_READ_WRITER_PASSWORD", "compat-read-writer")
          // the PNCP is the fixture, at a pace the test can wait for
          .withEnv("RADAR_PNCP_BASE_URL", "http://pncp:8080")
          .withEnv("RADAR_PNCP_REQUESTS_PER_SECOND", "20")
          .withEnv("RADAR_INGEST_INCREMENTAL_ENABLED", "true")
          .withEnv("RADAR_INGEST_INCREMENTAL_INTERVAL", "3s")
          .withEnv("RADAR_INGEST_CLOSE_EXPIRED_ENABLED", "false")
          // no broker in this test: the relay and the Kafka listeners stay off
          .withEnv("RADAR_OUTBOX_RELAY_ENABLED", "false")
          .withEnv("RADAR_SEARCH_PROJECTOR_ENABLED", "false")
          .withEnv("RADAR_ALERTS_MATCHER_ENABLED", "false")
          .withEnv("RADAR_ALERTS_NOTIFIER_ENABLED", "false")
          .withEnv("RADAR_OPS_DLQ_RECORDER_ENABLED", "false")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L << 20))
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/actuator/health/liveness")
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void start() throws SQLException {
    DB.start();
    // every migration of this release, as a deploy of it would have run them
    Flyway.configure()
        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
        .locations("classpath:db/migration")
        .placeholders(Map.of("readWriterPassword", "compat-read-writer"))
        .load()
        .migrate();
    jdbc =
        new JdbcTemplate(
            new SingleConnectionDataSource(
                DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()),
                true));
    PNCP.start();
    APP.start();
  }

  @AfterAll
  static void stop() {
    APP.stop();
    PNCP.stop();
    DB.stop();
    NETWORK.close();
  }

  private static List<Map<String, Object>> rows() {
    return jdbc.queryForList(
        "select numero_controle_pncp, estimated_value, estimated_value_cents"
            + " from ingest.procurement order by numero_controle_pncp");
  }

  @Test
  void theSchemaIsExpandedBeforeThePreviousReleaseStarts() {
    assertThat(
            jdbc.queryForObject(
                "select max(version::int) from flyway_schema_history", Integer.class))
        .isGreaterThanOrEqualTo(14);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'ingest'"
                    + " and table_name = 'procurement' and column_name = 'estimated_value_cents'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void thePreviousReleaseStartsOnTheExpandedSchemaAndLeavesTheHistoryAlone() {
    // started: @BeforeAll waited for liveness. Flyway ignored the migrations it does not know
    // (default ignoreMigrationPatterns *:future) and neither repaired nor applied anything
    assertThat(APP.isRunning()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class))
        .isZero();
    // Flyway saw version 14 it has no file for, called it newer and migrated nothing
    assertThat(APP.getLogs())
        .contains("that is newer than the latest available migration")
        .contains("No migration necessary")
        .doesNotContain("FlywayValidateException");
  }

  @Test
  void theNoticesItWritesCarryTheirCentsFromTheTrigger() {
    await()
        .atMost(Duration.ofMinutes(2))
        .untilAsserted(
            () ->
                assertThat(rows())
                    .as("ingested by the previous release; app log:%n%s", APP.getLogs())
                    .hasSize(3));

    assertThat(rows())
        .extracting(r -> r.get("estimated_value_cents"))
        .doesNotContainNull()
        .isNotEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ingest.procurement"
                    + " where estimated_value_cents is distinct from round(estimated_value * 100)",
                Integer.class))
        .isZero();
  }

  @Test
  void anUpdateOfTheValueByThePreviousReleaseMovesTheCents() {
    // the second read of the fixture revises the first notice to 250000.55
    await()
        .atMost(Duration.ofMinutes(2))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForList(
                            "select estimated_value_cents from ingest.procurement"
                                + " where numero_controle_pncp = '77817054000179-1-000116/2026'",
                            Long.class))
                    .as("revised by the previous release; app log:%n%s", APP.getLogs())
                    .containsExactly(25000055L));
  }
}
