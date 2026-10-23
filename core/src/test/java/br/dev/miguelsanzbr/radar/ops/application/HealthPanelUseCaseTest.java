package br.dev.miguelsanzbr.radar.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ops.application.port.ConsumerLags;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.application.port.FlagStates;
import br.dev.miguelsanzbr.radar.ops.application.port.OutboxStats;
import br.dev.miguelsanzbr.radar.ops.application.port.PipelineStats;
import br.dev.miguelsanzbr.radar.ops.domain.Checkpoint;
import br.dev.miguelsanzbr.radar.ops.domain.Component;
import br.dev.miguelsanzbr.radar.ops.domain.ConsumerLag;
import br.dev.miguelsanzbr.radar.ops.domain.Cursor;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import br.dev.miguelsanzbr.radar.ops.domain.FlagState;
import br.dev.miguelsanzbr.radar.ops.domain.HealthPanel;
import br.dev.miguelsanzbr.radar.ops.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ops.domain.OutboxBacklog;
import br.dev.miguelsanzbr.radar.ops.domain.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthPanelUseCaseTest {

  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");
  private static final List<String> DLQS =
      List.of("radar.procurement.v1.dlq", "radar.alert.v1.dlq");

  private final OutboxStats outbox = mock(OutboxStats.class);
  private final PipelineStats pipeline = mock(PipelineStats.class);
  private final ConsumerLags lags = mock(ConsumerLags.class);
  private final DlqMessages dlq = mock(DlqMessages.class);
  private final FlagStates flags = mock(FlagStates.class);
  private final HealthPanelUseCase useCase =
      new HealthPanelUseCase(outbox, pipeline, lags, dlq, flags, DLQS);

  private static IngestionRun run(String status) {
    return new IngestionRun("INCREMENTAL", 6, status, 10, 2, 3, 5, AT.minusSeconds(60), AT);
  }

  private void everythingHealthy() {
    when(outbox.read()).thenReturn(new OutboxBacklog(3, 1.5, 0));
    when(pipeline.lastRuns()).thenReturn(List.of(run("SUCCEEDED")));
    when(pipeline.cursors()).thenReturn(List.of(new Cursor(6, AT)));
    when(pipeline.checkpoints()).thenReturn(List.of(new Checkpoint("search", 42L, AT)));
    when(lags.read())
        .thenReturn(List.of(new ConsumerLag("search-projector", "radar.procurement.v1", 7)));
    when(dlq.unreprocessed()).thenReturn(List.of());
    when(flags.states()).thenReturn(List.of(new FlagState("ALERT_DIGEST", true)));
  }

  private static Component component(HealthPanel panel, String name) {
    return panel.components().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void everythingHealthyIsUpWithEverySectionFilled() {
    everythingHealthy();

    var panel = useCase.read();

    assertThat(panel.status()).isEqualTo(Status.UP);
    assertThat(panel.components())
        .extracting(Component::name)
        .containsExactly("outbox", "ingestion", "consumers", "dlq", "projection", "flags");
    assertThat(panel.components()).extracting(Component::status).containsOnly(Status.UP);
    assertThat(panel.outbox()).isEqualTo(new OutboxBacklog(3, 1.5, 0));
    assertThat(panel.lastRuns()).containsExactly(run("SUCCEEDED"));
    assertThat(panel.cursors()).containsExactly(new Cursor(6, AT));
    assertThat(panel.consumers())
        .containsExactly(new ConsumerLag("search-projector", "radar.procurement.v1", 7));
    assertThat(panel.projection()).containsExactly(new Checkpoint("search", 42L, AT));
    assertThat(panel.flags()).containsExactly(new FlagState("ALERT_DIGEST", true));
  }

  @Test
  void everyDeadLetterTopicIsListedWithZeroWhenNothingWaits() {
    everythingHealthy();
    when(dlq.unreprocessed())
        .thenReturn(List.of(new DlqCount("radar.alert.v1.dlq", "alerts-notifier", 2)));

    var panel = useCase.read();

    assertThat(panel.dlq())
        .containsExactly(
            new DlqCount("radar.alert.v1.dlq", "alerts-notifier", 2),
            new DlqCount("radar.procurement.v1.dlq", null, 0));
    assertThat(component(panel, "dlq").status()).isEqualTo(Status.DEGRADED);
    assertThat(panel.status()).isEqualTo(Status.DEGRADED);
  }

  @Test
  void anOldestMessageOlderThanTheSloOrAPoisonedOneDegradesTheOutbox() {
    everythingHealthy();
    when(outbox.read()).thenReturn(new OutboxBacklog(3, 5.1, 0));
    assertThat(component(useCase.read(), "outbox").status()).isEqualTo(Status.DEGRADED);

    when(outbox.read()).thenReturn(new OutboxBacklog(3, 5.0, 1));
    assertThat(component(useCase.read(), "outbox").status()).isEqualTo(Status.DEGRADED);

    when(outbox.read()).thenReturn(new OutboxBacklog(3, 5.0, 0));
    assertThat(component(useCase.read(), "outbox").status()).isEqualTo(Status.UP);
  }

  @Test
  void aFailedLastRunDegradesTheIngestion() {
    everythingHealthy();
    when(pipeline.lastRuns()).thenReturn(List.of(run("SUCCEEDED"), run("FAILED")));

    assertThat(component(useCase.read(), "ingestion").status()).isEqualTo(Status.DEGRADED);
  }

  @Test
  void aLagAboveTheLimitDegradesTheConsumers() {
    everythingHealthy();
    when(lags.read()).thenReturn(List.of(new ConsumerLag("g", "t", 1_000)));
    assertThat(component(useCase.read(), "consumers").status()).isEqualTo(Status.UP);

    when(lags.read()).thenReturn(List.of(new ConsumerLag("g", "t", 1_001)));
    assertThat(component(useCase.read(), "consumers").status()).isEqualTo(Status.DEGRADED);
  }

  @Test
  void noCheckpointYetIsNotAProblem() {
    everythingHealthy();
    when(pipeline.checkpoints()).thenReturn(List.of());

    var projection = component(useCase.read(), "projection");

    assertThat(projection.status()).isEqualTo(Status.UP);
    assertThat(projection.detail()).contains("nenhum");
  }

  @Test
  void theFlagsComponentCountsTheOnesThatAreOn() {
    everythingHealthy();
    when(flags.states()).thenReturn(List.of(new FlagState("A", true), new FlagState("B", false)));

    assertThat(component(useCase.read(), "flags").detail()).isEqualTo("1 de 2 ligadas");
  }

  @Test
  void aSourceThatFailsIsDownAndTheOtherSectionsStillAnswer() {
    everythingHealthy();
    when(lags.read()).thenThrow(new IllegalStateException("broker unreachable"));
    when(pipeline.lastRuns()).thenThrow(new IllegalStateException());

    var panel = useCase.read();

    assertThat(component(panel, "consumers"))
        .isEqualTo(
            new Component("consumers", Status.DOWN, "IllegalStateException: broker unreachable"));
    assertThat(component(panel, "ingestion").status()).isEqualTo(Status.DOWN);
    assertThat(component(panel, "ingestion").detail()).isEqualTo("IllegalStateException");
    assertThat(panel.consumers()).isEmpty();
    assertThat(panel.lastRuns()).isEmpty();
    assertThat(panel.outbox()).isEqualTo(new OutboxBacklog(3, 1.5, 0));
    assertThat(panel.status()).isEqualTo(Status.DOWN);
  }

  @Test
  void aFailedOutboxReadLeavesTheSectionEmpty() {
    everythingHealthy();
    when(outbox.read()).thenThrow(new IllegalStateException("no gauge"));

    var panel = useCase.read();

    assertThat(panel.outbox()).isNull();
    assertThat(component(panel, "outbox").status()).isEqualTo(Status.DOWN);
  }
}
