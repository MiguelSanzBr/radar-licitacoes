package br.dev.miguelsanzbr.radar.searchapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The search-api with the production switches: nothing that gates a feature is turned off (the
 * projector, the matcher and notifier, the rate limit, the detail cache), only what needs a live
 * database or broker to start (a dead data source, listeners not started).
 */
class SearchApiProductionContextTest {

  static final String[] WITHOUT_INFRASTRUCTURE = {
    "--server.port=0",
    "--spring.main.banner-mode=off",
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none",
    "--spring.datasource.username=none",
    "--spring.datasource.password=none",
    "--spring.datasource.hikari.initialization-fail-timeout=-1",
    "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
    "--spring.jpa.hibernate.ddl-auto=none",
    "--spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
    "--spring.kafka.listener.auto-startup=false",
    "--spring.kafka.admin.auto-create=false",
    "--spring.kafka.admin.fail-fast=false",
    "--management.tracing.export.enabled=false"
  };

  private static ConfigurableApplicationContext context;

  @BeforeAll
  static void start() {
    context =
        new SpringApplicationBuilder(SearchApiApplication.class)
            .profiles("search-api")
            .run(WITHOUT_INFRASTRUCTURE);
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @Test
  void runsTheConsumersTheRateLimitAndTheDetailCacheButNotTheRelay() {
    assertThat(beanTypes())
        .anyMatch(type -> type.endsWith(".ProcurementEventsListener"))
        .anyMatch(type -> type.endsWith(".ProcurementEventsForAlertsListener"))
        .anyMatch(type -> type.endsWith(".AlertMatchedListener"))
        .anyMatch(type -> type.endsWith(".DlqRecorderListener"))
        .anyMatch(type -> type.endsWith(".RateLimitFilter"))
        .anyMatch(type -> type.endsWith(".RedisDetailCache"))
        .noneMatch(type -> type.endsWith(".OutboxScheduler"));
  }

  private static String[] beanTypes() {
    return Arrays.stream(context.getBeanDefinitionNames())
        .map(context::getType)
        .filter(type -> type != null)
        .map(Class::getName)
        .toArray(String[]::new);
  }
}
