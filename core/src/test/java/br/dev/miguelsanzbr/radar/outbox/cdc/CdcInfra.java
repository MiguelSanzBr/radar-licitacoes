package br.dev.miguelsanzbr.radar.outbox.cdc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * Dedicated Postgres (logical WAL), Kafka and Debezium Connect on one network, one set per JVM.
 * They are never Spring beans: Boot would stop them with the first context that closes. The
 * connector reads the very files of {@code infra/cdc} and the image pinned in the compose file, so
 * the test exercises what the compose profile runs.
 */
public final class CdcInfra {

  private static final Path INFRA = Path.of("..", "infra");
  private static final String PASSWORD = UUID.randomUUID().toString();

  public static final Network NETWORK = Network.newNetwork();

  public static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18")
          .withNetwork(NETWORK)
          .withNetworkAliases("postgres")
          .withDatabaseName("radar")
          .withUsername("radar")
          .withPassword(PASSWORD)
          .withCommand(
              "postgres",
              "-c",
              "wal_level=logical",
              "-c",
              "max_connections=100",
              "-c",
              "max_slot_wal_keep_size=1GB");

  public static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.1.0")
          .withNetwork(NETWORK)
          .withNetworkAliases("kafka")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx384m");

  private static GenericContainer<?> connector;

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  private CdcInfra() {}

  /** The Debezium image of the compose file, with its digest. */
  private static String image() throws IOException {
    try (var in = Files.newInputStream(INFRA.resolve("compose/docker-compose.yml"))) {
      var services = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(in)).get("services");
      return String.valueOf(((Map<?, ?>) services.get("debezium")).get("image"));
    }
  }

  /**
   * Starts the connector once and returns when its slot is active, so rows appended afterwards are
   * not missed (there is no snapshot). The schema must exist: start the application first.
   */
  public static synchronized GenericContainer<?> startConnector() {
    if (connector == null) {
      try {
        // why: the compose Kafka listens on kafka:9092; the test broker's in-network listener is
        // 9093
        var worker =
            Files.readString(INFRA.resolve("cdc/worker.properties"))
                .replace("bootstrap.servers=kafka:9092", "bootstrap.servers=kafka:9093");
        connector =
            new GenericContainer<>(DockerImageName.parse(image()))
                .withNetwork(NETWORK)
                .withCreateContainerCmdModifier(
                    cmd -> cmd.withEntrypoint("/kafka/bin/connect-standalone.sh"))
                .withCommand("/config/worker.properties", "/config/connector.properties")
                .withCopyToContainer(Transferable.of(worker), "/config/worker.properties")
                .withCopyFileToContainer(
                    MountableFile.forHostPath(INFRA.resolve("cdc/connector.properties")),
                    "/config/connector.properties")
                .withCopyToContainer(Transferable.of(PASSWORD), "/run/secrets/db_password")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms64m -Xmx256m")
                .withExposedPorts(8083)
                .waitingFor(
                    Wait.forHttp("/connectors/radar-outbox/status")
                        .forPort(8083)
                        .forResponsePredicate(
                            body -> body.contains("\"tasks\":[{\"id\":0,\"state\":\"RUNNING\""))
                        .withStartupTimeout(Duration.ofMinutes(3)));
        connector.start();
        awaitActiveSlot();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return connector;
  }

  private static void awaitActiveSlot() {
    var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    try (var connection = POSTGRES.createConnection("");
        var statement = connection.createStatement()) {
      while (System.nanoTime() < deadline) {
        try (var rows =
            statement.executeQuery(
                "SELECT 1 FROM pg_replication_slots WHERE slot_name = 'radar_outbox' AND active")) {
          if (rows.next()) {
            return;
          }
        }
        Thread.sleep(200);
      }
      throw new IllegalStateException("the radar_outbox slot never became active");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
