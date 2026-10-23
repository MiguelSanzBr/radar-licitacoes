package br.dev.miguelsanzbr.radar;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis per JVM (same image and digest as infra/compose); tests reach it by connection details.
 */
public final class SharedRedis {

  public static final String IMAGE =
      "redis:8.8-alpine@sha256:dc95ea2fcf5d5c4253be0f967c28143d0f625846197eed2fd32c748eb99abfca";

  public static final GenericContainer<?> INSTANCE =
      new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);

  static {
    INSTANCE.start();
  }

  private SharedRedis() {}
}
