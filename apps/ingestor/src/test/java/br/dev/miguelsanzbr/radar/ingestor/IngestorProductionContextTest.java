package br.dev.miguelsanzbr.radar.ingestor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The ingestor with the production switches: nothing that gates a feature is turned off (the
 * schedulers, the relay), only what needs a live database or broker to start (a dead data source,
 * no migrations, listeners not started).
 */
class IngestorProductionContextTest {

  static final String[] WITHOUT_INFRASTRUCTURE = {
    "--server.port=0",
    "--spring.main.banner-mode=off",
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none",
    "--spring.datasource.username=none",
    "--spring.datasource.password=none",
    "--spring.datasource.hikari.initialization-fail-timeout=-1",
    "--spring.flyway.enabled=false",
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
        new SpringApplicationBuilder(IngestorApplication.class)
            .profiles("ingestor")
            .run(WITHOUT_INFRASTRUCTURE);
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @Test
  void runsTheSchedulersTheRelayAndTheOperatorApi() {
    assertThat(beanTypes())
        .anyMatch(type -> type.endsWith(".OutboxScheduler"))
        .anyMatch(type -> type.endsWith(".ScheduledIncrementalSync"))
        .anyMatch(type -> type.endsWith(".ScheduledPartitionMaintenance"))
        .anyMatch(type -> type.endsWith(".BackfillController"))
        .noneMatch(type -> type.endsWith(".DlqRecorderListener"));
  }

  private static String[] beanTypes() {
    return Arrays.stream(context.getBeanDefinitionNames())
        .map(context::getType)
        .filter(type -> type != null)
        .map(Class::getName)
        .toArray(String[]::new);
  }
}
