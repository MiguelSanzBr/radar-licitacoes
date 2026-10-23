package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** The image runs with the json profile: it must not start on the development password. */
class JsonProfileSecretsTest {

  private static final String FLYWAY = "spring.flyway.placeholders.readWriterPassword";
  private static final String SEARCH = "radar.search.datasource.password";

  private static StandardEnvironment environment(Map<String, Object> extra) {
    var environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(extra)));
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    return environment;
  }

  @Test
  void withoutTheProfileTheDevelopmentDefaultStillApplies() {
    var environment = environment(Map.of());

    assertThat(environment.getProperty(FLYWAY)).isEqualTo("radar_rw_dev");
    assertThat(environment.getProperty(SEARCH)).isEqualTo("radar_rw_dev");
  }

  @Test
  void theJsonProfileHasNoDefaultSoAMissingVariableFailsFast() {
    var environment = environment(Map.of("spring.profiles.active", "json"));
    // the test host may export the variable
    environment.getPropertySources().remove("systemEnvironment");

    assertThatThrownBy(() -> environment.getProperty(FLYWAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RADAR_READ_WRITER_PASSWORD");
    assertThatThrownBy(() -> environment.getProperty(SEARCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RADAR_READ_WRITER_PASSWORD");
  }

  @Test
  void theJsonProfileTakesTheVariable() {
    var environment =
        environment(
            Map.of("spring.profiles.active", "json", "RADAR_READ_WRITER_PASSWORD", "from-env"));

    assertThat(environment.getProperty(FLYWAY)).isEqualTo("from-env");
    assertThat(environment.getProperty(SEARCH)).isEqualTo("from-env");
  }

  @Test
  void aMountedSecretFileStillWinsOverTheProfile() {
    var environment =
        environment(
            Map.of("spring.profiles.active", "json", FLYWAY, "from-file", SEARCH, "from-file"));

    assertThat(environment.getProperty(FLYWAY)).isEqualTo("from-file");
    assertThat(environment.getProperty(SEARCH)).isEqualTo("from-file");
  }
}
