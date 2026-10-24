package br.dev.miguelsanzbr.radar.ingestor;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ops.application.HealthPanelUseCase;
import br.dev.miguelsanzbr.radar.outbox.OutboxRelay;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The ingestor context holds the write side and nothing of the read side (ADR-0028). It is started
 * by hand: the Spring test listeners of Togglz read the flag state from the database before each
 * test, and this context has none (the aot-train profile only lets the beans wire up).
 */
class IngestorContextTest {

  private static final String ROOT = "br.dev.miguelsanzbr.radar.";

  private static ConfigurableApplicationContext context;

  @BeforeAll
  static void start() {
    context =
        new SpringApplicationBuilder(IngestorApplication.class)
            .profiles("aot-train", "ingestor")
            .run("--server.port=0");
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @Test
  void runsIngestionTheOutboxRelayAndTheOperatorApi() {
    assertThat(context.getBeansOfType(BackfillUseCase.class)).hasSize(1);
    assertThat(context.getBeansOfType(IncrementalSyncUseCase.class)).hasSize(1);
    assertThat(beanTypes()).anyMatch(type -> type.endsWith(".BackfillController"));
    assertThat(context.getBeansOfType(OutboxRelay.class)).hasSize(1);
    assertThat(context.getBeansOfType(HealthPanelUseCase.class)).hasSize(1);
  }

  @Test
  void keepsTheSecurityChainsForTheOperatorRoutes() {
    assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(2);
  }

  @Test
  void containsNoBeanOfSearchAlertsOrTheIdentityAdapters() {
    assertThat(beanTypes())
        .noneMatch(type -> type.startsWith(ROOT + "search."))
        .noneMatch(type -> type.startsWith(ROOT + "alerts."))
        .noneMatch(type -> type.startsWith(ROOT + "identity.adapter."))
        .noneMatch(type -> type.startsWith(ROOT + "identity.application."))
        .noneMatch(type -> type.endsWith("DlqRecorderListener"));
  }

  private static String[] beanTypes() {
    return Arrays.stream(context.getBeanDefinitionNames())
        .map(context::getType)
        .filter(type -> type != null)
        .map(Class::getName)
        .toArray(String[]::new);
  }
}
