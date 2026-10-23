package br.dev.miguelsanzbr.radar.chaos;

import java.util.List;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Points the application at the proxies. Like the shared containers of the other tests, the
 * containers are not beans: Boot would stop them when the context closes.
 */
@TestConfiguration(proxyBeanMethods = false)
class ChaosTestConfig {

  @Bean
  JdbcConnectionDetails chaosPostgres() {
    return new JdbcConnectionDetails() {
      @Override
      public String getJdbcUrl() {
        return ChaosEnvironment.jdbcUrl();
      }

      @Override
      public String getUsername() {
        return ChaosEnvironment.USER;
      }

      @Override
      public String getPassword() {
        return ChaosEnvironment.PASSWORD;
      }
    };
  }

  @Bean
  KafkaConnectionDetails chaosKafka() {
    return () -> List.of(ChaosEnvironment.kafkaBootstrap());
  }
}
