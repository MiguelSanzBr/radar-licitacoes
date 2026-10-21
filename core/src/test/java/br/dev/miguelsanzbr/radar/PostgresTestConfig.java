package br.dev.miguelsanzbr.radar;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Points the data source at the shared container. It is deliberately not a container bean: Boot
 * stops Startable beans when their context closes or is evicted from the test cache, and the
 * container outlives every context of the JVM.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

  @Bean
  JdbcConnectionDetails sharedPostgresConnectionDetails() {
    var postgres = SharedPostgres.INSTANCE;
    return new JdbcConnectionDetails() {
      @Override
      public String getJdbcUrl() {
        return postgres.getJdbcUrl();
      }

      @Override
      public String getUsername() {
        return postgres.getUsername();
      }

      @Override
      public String getPassword() {
        return postgres.getPassword();
      }
    };
  }
}
