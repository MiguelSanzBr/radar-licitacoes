package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
            new SearchDataSourceProperties("", "radar_read_writer", "secret", 25), main)) {
      assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://main:5432/radar");
      assertThat(dataSource.getUsername()).isEqualTo("radar_read_writer");
      assertThat(dataSource.getPassword()).isEqualTo("secret");
      assertThat(dataSource.getPoolName()).isEqualTo("search");
      assertThat(dataSource.getMaximumPoolSize()).isEqualTo(25);
    }
  }

  @Test
  void anExplicitUrlWins() {
    try (var dataSource =
        configuration.searchDataSource(
            new SearchDataSourceProperties("jdbc:postgresql://replica:5432/radar", "u", "p", 10),
            main)) {
      assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://replica:5432/radar");
    }
  }

  @Test
  void thePoolSizeIsTenUnlessConfigured() {
    var defaults = bind(Map.of("radar.search.datasource.password", "p"));
    var sized =
        bind(
            Map.of(
                "radar.search.datasource.password", "p",
                "radar.search.datasource.maximum-pool-size", "40"));

    assertThat(defaults.maximumPoolSize()).isEqualTo(10);
    assertThat(sized.maximumPoolSize()).isEqualTo(40);
  }

  private static SearchDataSourceProperties bind(Map<String, String> source) {
    return new Binder(new MapConfigurationPropertySource(source))
        .bind("radar.search.datasource", SearchDataSourceProperties.class)
        .get();
  }
}
