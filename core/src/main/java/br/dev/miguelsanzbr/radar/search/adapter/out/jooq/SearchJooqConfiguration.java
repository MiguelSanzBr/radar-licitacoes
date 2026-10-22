package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Read side persistence: its own pool logging in as {@code radar_read_writer}, its own transaction
 * manager and the jOOQ context over them. Nothing here is primary, so the JPA side is untouched.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchDataSourceProperties.class)
class SearchJooqConfiguration {

  @Bean
  HikariDataSource searchDataSource(
      SearchDataSourceProperties properties, @Qualifier("dataSource") HikariDataSource main) {
    var url = properties.url().isBlank() ? main.getJdbcUrl() : properties.url();
    var dataSource =
        DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .url(url)
            .username(properties.username())
            .password(properties.password())
            .build();
    dataSource.setPoolName("search");
    return dataSource;
  }

  @Bean
  DataSourceTransactionManager searchTransactionManager(
      @Qualifier("searchDataSource") DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  /** Joins the Spring transaction of {@code searchTransactionManager} when one is active. */
  @Bean
  DSLContext searchDsl(@Qualifier("searchDataSource") DataSource dataSource) {
    return DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
  }
}
