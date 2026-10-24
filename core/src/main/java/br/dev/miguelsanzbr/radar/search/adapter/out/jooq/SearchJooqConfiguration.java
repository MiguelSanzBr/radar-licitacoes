package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    dataSource.setMaximumPoolSize(properties.maximumPoolSize());
    // why: the driver prepares a statement on the server after five runs and Postgres may then keep
    // one generic plan for it. The text search needs the plan of the term it carries: GIN for a
    // rare term, publication order for a common one (ADR-0011, "Perfil e otimização")
    dataSource.setConnectionInitSql("SET plan_cache_mode = force_custom_plan");
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

  /**
   * For the public reads: the same pool, but a query that runs past {@code timeout} is cancelled
   * (rounded up to whole seconds, the JDBC unit) and surfaces as {@link
   * br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException}. The projector keeps {@code
   * searchDsl}: a write must not be cut short by a limit meant for readers.
   */
  @Bean
  DSLContext searchReadDsl(
      @Qualifier("searchDataSource") DataSource dataSource,
      @Value("${radar.search.statement-timeout:3s}") Duration timeout) {
    var configuration =
        new DefaultConfiguration()
            .set(SQLDialect.POSTGRES)
            .set(new TransactionAwareDataSourceProxy(dataSource))
            .set(
                new Settings().withQueryTimeout(Math.toIntExact((timeout.toMillis() + 999) / 1000)))
            .set(new DefaultExecuteListenerProvider(new ReadTimeoutListener()));
    return DSL.using(configuration);
  }
}
