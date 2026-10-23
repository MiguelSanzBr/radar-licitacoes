package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-0018: the CDC alternative is wired in compose, behind a profile, without secrets in files.
 */
class CdcComposeTest {

  private static final Path INFRA = Path.of("..", "infra");

  private static Map<?, ?> services() throws IOException {
    try (var in = Files.newInputStream(INFRA.resolve("compose/docker-compose.yml"))) {
      return (Map<?, ?>) ((Map<?, ?>) new Yaml().load(in)).get("services");
    }
  }

  private static Properties properties(String name) throws IOException {
    var properties = new Properties();
    try (var in = Files.newInputStream(INFRA.resolve("cdc").resolve(name))) {
      properties.load(in);
    }
    return properties;
  }

  @Test
  void postgresRunsWithLogicalWalForTheSlot() throws IOException {
    var command =
        ((List<?>) ((Map<?, ?>) services().get("postgres")).get("command"))
            .stream().map(String::valueOf).toList();

    assertThat(command).contains("wal_level=logical");
  }

  @Test
  void debeziumIsInTheCdcProfileOnlyAndPinnedByDigest() throws IOException {
    var debezium = (Map<?, ?>) services().get("debezium");

    assertThat(debezium.get("profiles")).isEqualTo(List.of("cdc"));
    assertThat(String.valueOf(debezium.get("image")))
        .matches("quay\\.io/debezium/connect:3\\.6\\.\\d+\\.Final@sha256:[0-9a-f]{64}");
    assertThat(debezium.get("secrets")).isEqualTo(List.of("db_password"));
    assertThat(((Map<?, ?>) debezium.get("environment")).keySet())
        .allSatisfy(
            name -> assertThat(String.valueOf(name)).doesNotContainIgnoringCase("password"));
  }

  @Test
  void aOneShotSetupAppliesTheScriptAfterTheAppAndBeforeDebezium() throws IOException {
    var services = services();
    var setup = (Map<?, ?>) services.get("cdc-setup");
    var debezium = (Map<?, ?>) services.get("debezium");
    var postgresImage = ((Map<?, ?>) services.get("postgres")).get("image");

    assertThat(setup.get("profiles")).isEqualTo(List.of("cdc"));
    assertThat(setup.get("image")).isEqualTo(postgresImage);
    assertThat(setup.get("restart")).isEqualTo("no");
    assertThat(((Map<?, ?>) setup.get("depends_on")).get("app"))
        .isEqualTo(Map.of("condition", "service_healthy"));
    assertThat(String.valueOf(setup.get("command"))).contains("/cdc/enable-cdc.sql");
    assertThat(((Map<?, ?>) debezium.get("depends_on")).get("cdc-setup"))
        .isEqualTo(Map.of("condition", "service_completed_successfully"));
  }

  @Test
  void flywayCreatesNeitherTheEnvelopeColumnNorThePublication() throws IOException {
    try (var files = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
      for (var file : files.toList()) {
        assertThat(Files.readString(file))
            .as(file.getFileName().toString())
            .doesNotContainIgnoringCase("CREATE PUBLICATION")
            .doesNotContain("build_envelope");
      }
    }
  }

  @Test
  void theScriptsAreIdempotentAndPairedAndTheHeartbeatKeepsTheSlotMoving() throws IOException {
    var enable = Files.readString(INFRA.resolve("cdc/enable-cdc.sql"));
    var disable = Files.readString(INFRA.resolve("cdc/disable-cdc.sql"));

    assertThat(enable)
        .contains("CREATE OR REPLACE FUNCTION outbox.build_envelope")
        .contains("ADD COLUMN IF NOT EXISTS envelope")
        .contains("STORED")
        .contains("CREATE PUBLICATION radar_outbox");
    assertThat(disable)
        .contains("DROP PUBLICATION IF EXISTS radar_outbox")
        .contains("DROP COLUMN IF EXISTS envelope")
        .contains("pg_drop_replication_slot");
    var connector = properties("connector.properties");
    assertThat(connector.getProperty("heartbeat.interval.ms")).isEqualTo("10000");
    // the beat must write a published table, or the slot does not move while the outbox is idle
    assertThat(connector.getProperty("heartbeat.action.query")).contains("outbox.cdc_heartbeat");
    assertThat(enable).contains("outbox.cdc_heartbeat");
    assertThat(disable).contains("DROP TABLE IF EXISTS outbox.cdc_heartbeat");
  }

  @Test
  void theAppSwitchesToCdcOnlyThroughTheModeVariable() throws IOException {
    var environment = (Map<?, ?>) ((Map<?, ?>) services().get("app")).get("environment");

    assertThat(environment.get("RADAR_OUTBOX_MODE")).isEqualTo("${RADAR_OUTBOX_MODE:-polling}");
  }

  @Test
  void connectorReadsOnlyTheOutboxTableThroughItsOwnSlotAndPublication() throws IOException {
    var connector = properties("connector.properties");

    assertThat(connector.getProperty("connector.class"))
        .isEqualTo("io.debezium.connector.postgresql.PostgresConnector");
    assertThat(connector.getProperty("plugin.name")).isEqualTo("pgoutput");
    assertThat(connector.getProperty("table.include.list")).isEqualTo("outbox.outbox_event");
    assertThat(connector.getProperty("slot.name")).isEqualTo("radar_outbox");
    assertThat(connector.getProperty("publication.name")).isEqualTo("radar_outbox");
    assertThat(connector.getProperty("publication.autocreate.mode")).isEqualTo("disabled");
    assertThat(connector.getProperty("snapshot.mode")).isEqualTo("no_data");
    assertThat(connector.getProperty("transforms.outbox.type"))
        .isEqualTo("io.debezium.transforms.outbox.EventRouter");
    assertThat(connector.getProperty("transforms.outbox.table.field.event.key"))
        .isEqualTo("aggregate_id");
    assertThat(connector.getProperty("transforms.outbox.table.field.event.payload"))
        .isEqualTo("envelope");
  }

  @Test
  void thePasswordComesFromTheSecretFileNeverFromTheProperties() throws IOException {
    var connector = properties("connector.properties");
    var worker = properties("worker.properties");

    assertThat(connector.getProperty("database.password"))
        .isEqualTo("${directory:/run/secrets:db_password}");
    assertThat(worker.getProperty("config.providers.directory.class"))
        .isEqualTo("org.apache.kafka.common.config.provider.DirectoryConfigProvider");
  }

  @Test
  void keysValuesAndHeadersLeaveAsPlainStringsLikeTheRelayWritesThem() throws IOException {
    var worker = properties("worker.properties");

    assertThat(worker.getProperty("key.converter"))
        .isEqualTo("org.apache.kafka.connect.storage.StringConverter");
    assertThat(worker.getProperty("value.converter"))
        .isEqualTo("org.apache.kafka.connect.storage.StringConverter");
    assertThat(worker.getProperty("header.converter"))
        .isEqualTo("org.apache.kafka.connect.storage.StringConverter");
  }
}
