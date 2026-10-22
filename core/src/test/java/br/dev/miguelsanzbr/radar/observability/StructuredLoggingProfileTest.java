package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class StructuredLoggingProfileTest {

  private static String consoleFormat(Map<String, Object> properties) {
    var environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    return environment.getProperty("logging.structured.format.console");
  }

  @Test
  void theJsonProfileTurnsOnStructuredConsoleLogs() {
    assertThat(consoleFormat(Map.of("spring.profiles.active", "json"))).isEqualTo("logstash");
  }

  @Test
  void withoutTheProfileConsoleLogsStayPlainText() {
    assertThat(consoleFormat(Map.of())).isNull();
  }
}
