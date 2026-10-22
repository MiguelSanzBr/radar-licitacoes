package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduledIncrementalSyncTest {

  private final IncrementalSyncUseCase useCase = mock(IncrementalSyncUseCase.class);
  private final IngestScheduleProperties properties =
      new IngestScheduleProperties(
          List.of(6, 12), new IngestScheduleProperties.Incremental(Duration.ZERO, Duration.ZERO));
  private final ScheduledIncrementalSync scheduled =
      new ScheduledIncrementalSync(useCase, properties);

  private static IngestionRun run() {
    var now = Instant.parse("2026-10-22T12:00:00Z");
    return IngestionRun.start(
        IngestionKind.INCREMENTAL, 6, now.minusSeconds(900), now, Clock.fixed(now, ZoneOffset.UTC));
  }

  @BeforeEach
  void stubRuns() {
    when(useCase.sync(anyInt())).thenReturn(run());
  }

  @Test
  void syncsEveryConfiguredModalityInOrder() {
    scheduled.run();

    var order = inOrder(useCase);
    order.verify(useCase).sync(6);
    order.verify(useCase).sync(12);
  }

  @Test
  void aFailingModalityIsLoggedAndDoesNotStopTheNext() {
    when(useCase.sync(6)).thenThrow(new PncpUnavailableException(503, "down", null));

    assertThatCode(scheduled::run).doesNotThrowAnyException();

    inOrder(useCase).verify(useCase).sync(12);
  }
}
