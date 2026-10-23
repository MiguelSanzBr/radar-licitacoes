package br.dev.miguelsanzbr.radar.ops.adapter.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.togglz.core.repository.StateRepository;
import org.togglz.core.repository.jdbc.JDBCStateRepository;

/**
 * Where the flag states live: {@code ops.togglz_feature}, created by Flyway. Which features exist
 * and their initial state come from {@code togglz.features.*} in application.properties.
 */
@Configuration(proxyBeanMethods = false)
class TogglzConfig {

  @Bean
  StateRepository togglzStateRepository(@Qualifier("dataSource") DataSource dataSource) {
    return new JDBCStateRepository.Builder(dataSource)
        .tableName("ops.togglz_feature")
        .createTable(false)
        .build();
  }
}
