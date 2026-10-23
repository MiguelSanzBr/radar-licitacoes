package br.dev.miguelsanzbr.radar.chaos;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * The infrastructure of the chaos test, one set per JVM and nothing shared with the other tests:
 * its own Postgres and Kafka, which the test restarts, and a Toxiproxy between them and everything
 * else. The application only ever sees the proxy ports, so a fault on a proxy is a fault on the
 * link and a container restart is invisible to the connection details.
 *
 * <p>Toxiproxy starts first because Kafka must advertise the proxy address its clients will use.
 */
final class ChaosEnvironment {

  // 2.12.0, pinned by digest
  private static final String TOXIPROXY_IMAGE =
      "ghcr.io/shopify/toxiproxy@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e";

  /** What the application reaches, as {@code localhost:port}. */
  private static final int PG_LISTEN = 8666;

  private static final int KAFKA_LISTEN = 8667;
  private static final int PNCP_LISTEN = 8668;

  /** A second way to Postgres that never gets a fault, for the test to look at the data. */
  private static final int OBSERVER_LISTEN = 8669;

  static final String DATABASE = "radar";
  static final String USER = "radar";
  static final String PASSWORD = "radar";

  static final Network NETWORK = Network.newNetwork();

  static final ToxiproxyContainer TOXIPROXY =
      new ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(NETWORK).withNetworkAliases("toxiproxy");

  // 3.13.2-alpine, pinned by digest
  // why: a container, not a server in the test JVM: the host firewall can refuse connections
  // from the Docker networks to the host, and the proxy has to reach the PNCP stand-in
  static final GenericContainer<?> PNCP_STANDIN =
      new GenericContainer<>(
              "wiremock/wiremock@sha256:f8c42a38dca3f4a1d7219af11c80438740f39eebb1505b0f029aed743c20e147")
          .withNetwork(NETWORK)
          .withNetworkAliases("pncp")
          .withEnv("JAVA_OPTS", "-Xmx384m")
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/__admin/mappings").forPort(8080));

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18")
          .withDatabaseName(DATABASE)
          .withUsername(USER)
          .withPassword(PASSWORD)
          .withNetwork(NETWORK)
          .withNetworkAliases("pg")
          .withCommand("postgres", "-c", "max_connections=200");

  static final GenericContainer<?> KAFKA;

  static final Proxy PG;
  static final Proxy KAFKA_PROXY;
  static final Proxy PNCP;

  static {
    TOXIPROXY.start();
    try {
      var client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
      PG = client.createProxy("postgres", "0.0.0.0:" + PG_LISTEN, "pg:5432");
      client.createProxy("postgres-observer", "0.0.0.0:" + OBSERVER_LISTEN, "pg:5432");
      KAFKA_PROXY = client.createProxy("kafka", "0.0.0.0:" + KAFKA_LISTEN, "kafka:9092");
      PNCP = client.createProxy("pncp", "0.0.0.0:" + PNCP_LISTEN, "pncp:8080");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    POSTGRES.start();
    PNCP_STANDIN.start();
    KAFKA =
        new GenericContainer<>("apache/kafka:4.1.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withEnv(
                Map.ofEntries(
                    Map.entry("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m"),
                    Map.entry("KAFKA_NODE_ID", "1"),
                    Map.entry("KAFKA_PROCESS_ROLES", "broker,controller"),
                    Map.entry(
                        "KAFKA_LISTENERS",
                        "CLIENT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094"),
                    Map.entry(
                        "KAFKA_ADVERTISED_LISTENERS",
                        "CLIENT://" + kafkaBootstrap() + ",BROKER://kafka:9093"),
                    Map.entry(
                        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "CLIENT:PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT"),
                    Map.entry("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER"),
                    Map.entry("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER"),
                    Map.entry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9094"),
                    Map.entry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"),
                    Map.entry("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1"),
                    Map.entry("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1"),
                    Map.entry("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")))
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1));
    KAFKA.start();
  }

  private ChaosEnvironment() {}

  static String jdbcUrl() {
    return jdbcUrl(PG_LISTEN);
  }

  static String observerJdbcUrl() {
    return jdbcUrl(OBSERVER_LISTEN);
  }

  private static String jdbcUrl(int listen) {
    return "jdbc:postgresql://%s:%d/%s"
        .formatted(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(listen), DATABASE);
  }

  /** What Kafka clients use, and what the broker advertises. */
  static String kafkaBootstrap() {
    return "%s:%d".formatted(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(KAFKA_LISTEN));
  }

  static String pncpBaseUrl() {
    return "http://%s:%d".formatted(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(PNCP_LISTEN));
  }

  /** Stops the container and starts it again; the data stays, the proxy port does not change. */
  static void restart(GenericContainer<?> container, Duration downFor) {
    var docker = container.getDockerClient();
    docker.stopContainerCmd(container.getContainerId()).withTimeout(10).exec();
    pause(downFor);
    docker.startContainerCmd(container.getContainerId()).exec();
  }

  /** Waits until Postgres answers through the observer proxy. */
  static void awaitPostgres(Duration atMost) {
    Awaitility.await()
        .atMost(atMost)
        .pollInterval(Duration.ofMillis(250))
        .ignoreExceptions()
        .until(
            () -> {
              try (var connection =
                  DriverManager.getConnection(
                      observerJdbcUrl() + "?connectTimeout=2", USER, PASSWORD)) {
                return connection.isValid(2);
              }
            });
  }

  /** Waits until the broker answers a metadata request through its proxy. */
  static void awaitKafka(Duration atMost) {
    Awaitility.await()
        .atMost(atMost)
        .pollInterval(Duration.ofMillis(500))
        .ignoreExceptions()
        .until(
            () -> {
              try (var admin =
                  Admin.create(
                      Map.<String, Object>of(
                          AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                          kafkaBootstrap(),
                          AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                          3000))) {
                return !admin.describeCluster().nodes().get(3, TimeUnit.SECONDS).isEmpty();
              }
            });
  }

  static void pause(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
