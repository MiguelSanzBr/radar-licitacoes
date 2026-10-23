package br.dev.miguelsanzbr.radar.search.adapter.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The binder of the configuration properties leaves an unresolved {@code ${...}} as literal text,
 * so the json profile alone would start the image with that text as the password of the read role.
 */
class ReadWriterPasswordCheckTest {

  private final ReadWriterPasswordCheck check = new ReadWriterPasswordCheck();

  private static ConfigurableEnvironment environment(Map<String, Object> properties) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", new HashMap<>(properties)));
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    // the test host may export the variable
    environment.getPropertySources().remove("systemEnvironment");
    return environment;
  }

  @Test
  void theJsonProfileWithoutThePasswordRefusesToStart() {
    var environment = environment(Map.of("spring.profiles.active", "json"));

    assertThatThrownBy(() -> check.postProcessEnvironment(environment, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RADAR_READ_WRITER_PASSWORD");
  }

  @Test
  void aBlankPasswordIsRefusedToo() {
    var environment =
        environment(Map.of("spring.profiles.active", "json", "RADAR_READ_WRITER_PASSWORD", " "));

    assertThatThrownBy(() -> check.postProcessEnvironment(environment, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theJsonProfileWithThePasswordStarts() {
    var environment =
        environment(Map.of("spring.profiles.active", "json", "RADAR_READ_WRITER_PASSWORD", "x"));

    assertThatCode(() -> check.postProcessEnvironment(environment, null))
        .doesNotThrowAnyException();
  }

  @Test
  void aMountedSecretFileCountsAsThePassword() {
    var environment =
        environment(
            Map.of(
                "spring.profiles.active",
                "json",
                "spring.flyway.placeholders.readWriterPassword",
                "from-file",
                "radar.search.datasource.password",
                "from-file"));

    assertThatCode(() -> check.postProcessEnvironment(environment, null))
        .doesNotThrowAnyException();
  }

  @Test
  void otherProfilesKeepTheDevelopmentDefault() {
    var environment = environment(Map.of());

    assertThatCode(() -> check.postProcessEnvironment(environment, null))
        .doesNotThrowAnyException();
  }
}
