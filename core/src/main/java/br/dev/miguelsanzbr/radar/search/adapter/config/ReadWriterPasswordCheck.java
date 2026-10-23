package br.dev.miguelsanzbr.radar.search.adapter.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Stops the boot of the image (profile {@code json}) when the password of the read role is missing.
 * It runs before Flyway, which would otherwise create the role with the unresolved placeholder text
 * as password. Registered in {@code META-INF/spring.factories}.
 */
public class ReadWriterPasswordCheck implements EnvironmentPostProcessor {

  private static final String[] KEYS = {
    "spring.flyway.placeholders.readWriterPassword", "radar.search.datasource.password"
  };

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.acceptsProfiles(Profiles.of("json"))) {
      return;
    }
    for (var key : KEYS) {
      if (!hasValue(environment, key)) {
        throw new IllegalStateException(
            "RADAR_READ_WRITER_PASSWORD is required by the json profile (" + key + " is empty)");
      }
    }
  }

  private static boolean hasValue(ConfigurableEnvironment environment, String key) {
    try {
      return !environment.getProperty(key, "").isBlank();
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      return false;
    }
  }
}
