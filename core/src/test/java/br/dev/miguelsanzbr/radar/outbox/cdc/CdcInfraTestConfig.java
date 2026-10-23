package br.dev.miguelsanzbr.radar.outbox.cdc;

import java.util.List;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Points the context at the dedicated containers without making them Spring beans. */
@TestConfiguration(proxyBeanMethods = false)
public class CdcInfraTestConfig {

  @Bean
  JdbcConnectionDetails cdcPostgresConnectionDetails() {
    var postgres = CdcInfra.POSTGRES;
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

  @Bean
  KafkaConnectionDetails cdcKafkaConnectionDetails() {
    return () -> List.of(CdcInfra.KAFKA.getBootstrapServers());
  }
}
