package br.dev.miguelsanzbr.radar;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * The write side data source and transaction manager. The search context registers a second pair,
 * which makes Boot back off from its own, so the primary ones are declared here the way Boot would.
 */
@Configuration(proxyBeanMethods = false)
class PrimaryPersistenceConfiguration {

  /**
   * Boot publishes {@link JdbcConnectionDetails} only together with its own data source, so when
   * nothing else provides them (tests point at a container this way) they come from {@code
   * spring.datasource.*}.
   */
  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(
      DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> details) {
    var connection = details.getIfAvailable(() -> new PropertiesDetails(properties));
    return DataSourceBuilder.create()
        .type(HikariDataSource.class)
        .url(connection.getJdbcUrl())
        .username(connection.getUsername())
        .password(connection.getPassword())
        .build();
  }

  @Bean
  @Primary
  JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
    return new JpaTransactionManager(entityManagerFactory);
  }

  private record PropertiesDetails(DataSourceProperties properties)
      implements JdbcConnectionDetails {

    @Override
    public String getJdbcUrl() {
      return properties.determineUrl();
    }

    @Override
    public String getUsername() {
      return properties.determineUsername();
    }

    @Override
    public String getPassword() {
      return properties.determinePassword();
    }
  }
}
