package br.dev.miguelsanzbr.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The container is one per JVM: closing or evicting a context must never stop it. */
class SharedPostgresSurvivesContextCloseIT {

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(PostgresTestConfig.class)
  static class Probe {}

  @Test
  void closingAContextLeavesTheSharedContainerRunning() throws Exception {
    new SpringApplicationBuilder(Probe.class).web(WebApplicationType.NONE).run().close();

    var postgres = SharedPostgres.INSTANCE;
    assertThat(postgres.isRunning()).isTrue();
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var rows = connection.createStatement().executeQuery("SELECT 1")) {
      assertThat(rows.next()).isTrue();
    }
  }
}
