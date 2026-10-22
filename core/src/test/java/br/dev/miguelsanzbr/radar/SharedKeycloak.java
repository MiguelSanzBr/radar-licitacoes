package br.dev.miguelsanzbr.radar;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.time.Duration;

/**
 * One Keycloak per JVM with the test realm (the dev realm plus direct grants). Started only by the
 * classes that touch it; the application sees it through the issuer property, never as a bean.
 */
public final class SharedKeycloak {

  public static final KeycloakContainer INSTANCE =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8")
          .withRealmImportFile("keycloak/realm-radar-test.json")
          .withEnv("JAVA_OPTS_KC_HEAP", "-Xms64m -Xmx512m")
          // the first boot builds the server (about a minute); a loaded host needs more
          .withStartupTimeout(Duration.ofMinutes(5));

  static {
    INSTANCE.start();
  }

  private SharedKeycloak() {}

  public static String issuer() {
    return INSTANCE.getAuthServerUrl() + "/realms/radar";
  }
}
