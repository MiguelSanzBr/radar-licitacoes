package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import br.dev.miguelsanzbr.radar.SharedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exposes the shared container to the JPA slice as the data source. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return SharedPostgres.INSTANCE;
  }
}
