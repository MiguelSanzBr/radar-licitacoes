package br.dev.miguelsanzbr.radar;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One Apicurio Registry per JVM for the tests tagged {@code avro}. The image is the one of the
 * compose file; it has no global compatibility rule, so each test sets the rule of its own group.
 */
public final class SharedApicurio {

  public static final GenericContainer<?> INSTANCE =
      new GenericContainer<>(
              "apicurio/apicurio-registry:3.3.3@sha256:c9cae4c90ce46538abf673c68eb591345f23bcc6c2aa6bfd47189adcf609d8f1")
          .withExposedPorts(8080)
          .withEnv("JAVA_OPTS_APPEND", "-Xmx256m")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024))
          .waitingFor(
              Wait.forHttp("/apis/registry/v3/system/info")
                  .forPort(8080)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  static {
    INSTANCE.start();
  }

  private SharedApicurio() {}

  /**
   * The root of the registry, where the Confluent compatible API lives under {@code /apis/ccompat}.
   */
  public static String root() {
    return "http://" + INSTANCE.getHost() + ":" + INSTANCE.getMappedPort(8080);
  }

  /** The v3 API, what {@code radar.messaging.registry-url} points at. */
  public static String url() {
    return root() + "/apis/registry/v3";
  }
}
