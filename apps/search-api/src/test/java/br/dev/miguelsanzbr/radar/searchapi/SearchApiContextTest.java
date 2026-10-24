package br.dev.miguelsanzbr.radar.searchapi;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.application.MatchProcurementUseCase;
import br.dev.miguelsanzbr.radar.identity.application.ManageApiKeysUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ops.application.HealthPanelUseCase;
import br.dev.miguelsanzbr.radar.outbox.OutboxWriter;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The search-api context holds the read side and nothing of ingestion (ADR-0028). It is started by
 * hand (the Spring test listeners of Togglz need a database this context does not have); what would
 * dial out to a broker or to Redis stays off.
 */
class SearchApiContextTest {

  private static final String ROOT = "br.dev.miguelsanzbr.radar.";

  private static ConfigurableApplicationContext context;

  @BeforeAll
  static void start() {
    context =
        new SpringApplicationBuilder(SearchApiApplication.class)
            .profiles("aot-train", "search-api")
            .run("--server.port=0");
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @Test
  void servesSearchAlertsIdentityAndOperations() {
    assertThat(context.getBeansOfType(SearchUseCase.class)).hasSize(1);
    assertThat(context.getBeansOfType(MatchProcurementUseCase.class)).hasSize(1);
    assertThat(context.getBeansOfType(ManageApiKeysUseCase.class)).hasSize(1);
    assertThat(context.getBeansOfType(HealthPanelUseCase.class)).hasSize(1);
  }

  @Test
  void keepsTheOutboxWriterBecauseAlertsAppendToTheOutbox() {
    assertThat(context.getBeansOfType(OutboxWriter.class)).hasSize(1);
  }

  @Test
  void containsNoIngestBean() {
    assertThat(context.getBeansOfType(BackfillUseCase.class)).isEmpty();
    assertThat(beanTypes()).noneMatch(type -> type.startsWith(ROOT + "ingest."));
  }

  private static String[] beanTypes() {
    return Arrays.stream(context.getBeanDefinitionNames())
        .map(context::getType)
        .filter(type -> type != null)
        .map(Class::getName)
        .toArray(String[]::new);
  }
}
