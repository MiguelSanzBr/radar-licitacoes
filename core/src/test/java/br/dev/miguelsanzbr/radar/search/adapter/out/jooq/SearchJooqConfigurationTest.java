package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchJooqConfigurationTest {

  private final SearchJooqConfiguration configuration = new SearchJooqConfiguration();
  private final HikariDataSource main = new HikariDataSource();

  @AfterEach
  void closeMain() {
    main.close();
  }

  @Test
  void aBlankUrlMeansTheDatabaseOfTheWriteSide() {
    main.setJdbcUrl("jdbc:postgresql://main:5432/radar");

    try (var dataSource =
        configuration.searchDataSource(
            new SearchDataSourceProperties("", "radar_read_writer", "secret"), main)) {
      assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://main:5432/radar");
      assertThat(dataSource.getUsername()).isEqualTo("radar_read_writer");
      assertThat(dataSource.getPassword()).isEqualTo("secret");
      assertThat(dataSource.getPoolName()).isEqualTo("search");
    }
  }

  @Test
  void anExplicitUrlWins() {
    try (var dataSource =
        configuration.searchDataSource(
            new SearchDataSourceProperties("jdbc:postgresql://replica:5432/radar", "u", "p"),
            main)) {
      assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://replica:5432/radar");
    }
  }
}
