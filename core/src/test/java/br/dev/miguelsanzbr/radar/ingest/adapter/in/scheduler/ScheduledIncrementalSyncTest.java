package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncFailedException;
import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpBlockedException;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ops.Flag;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ScheduledIncrementalSyncTest {

  private final IncrementalSyncUseCase useCase = mock(IncrementalSyncUseCase.class);
  private final IngestScheduleProperties properties =
      new IngestScheduleProperties(
          List.of(6, 12), new IngestScheduleProperties.Incremental(Duration.ZERO, Duration.ZERO));
  private boolean on = true;
  private final ScheduledIncrementalSync scheduled =
      new ScheduledIncrementalSync(
          useCase, properties, flag -> flag == Flag.INCREMENTAL_SYNC && on);

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
  void whileTheFlagIsOffNothingIsSynced() {
    on = false;

    scheduled.run();

    verifyNoInteractions(useCase);
  }

  @Test
  void aFailingModalityIsLoggedAndDoesNotStopTheNext() {
    when(useCase.sync(6)).thenThrow(new PncpUnavailableException(503, "down", null));

    assertThatCode(scheduled::run).doesNotThrowAnyException();

    inOrder(useCase).verify(useCase).sync(12);
  }

  @Test
  void aSyncFailureThatIsNotABlockIsLoggedAndDoesNotStopTheNext() {
    when(useCase.sync(6))
        .thenThrow(
            new IncrementalSyncFailedException(
                6, 1, new PncpUnavailableException(503, "down", null)));

    assertThatCode(scheduled::run).doesNotThrowAnyException();

    inOrder(useCase).verify(useCase).sync(12);
  }

  private List<ILoggingEvent> logsOf(Runnable action) {
    var logs = new ListAppender<ILoggingEvent>();
    var logger = (Logger) LoggerFactory.getLogger(ScheduledIncrementalSync.class);
    logs.start();
    logger.addAppender(logs);
    try {
      action.run();
    } finally {
      logger.detachAppender(logs);
    }
    return logs.list;
  }

  private static final Instant UNTIL = Instant.parse("2026-10-22T12:30:00Z");

  @Test
  void aBlockedPncpSkipsTheWholeCycleWithOneWarnAndNoStackTrace() {
    var blocked = new PncpBlockedException(429, UNTIL, null);
    when(useCase.sync(6)).thenThrow(new IncrementalSyncFailedException(6, 1, blocked));

    var logs = logsOf(scheduled::run);

    verify(useCase, never()).sync(12);
    assertThat(logs)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("skipped")
                  .contains(UNTIL.toString())
                  .contains("pncp-bloqueado");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  @Test
  void whileTheGatewayIsBlockedNoRunIsStartedAndTheBlockIsAnnouncedOnce() {
    when(useCase.blockedUntil()).thenReturn(Optional.of(UNTIL));

    var logs =
        logsOf(
            () -> {
              scheduled.run();
              scheduled.run();
              scheduled.run();
            });

    verify(useCase, never()).sync(anyInt());
    assertThat(logs).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
  }

  @Test
  void aLongerBlockIsAnnouncedAgain() {
    when(useCase.blockedUntil())
        .thenReturn(Optional.of(UNTIL), Optional.of(UNTIL.plusSeconds(600)));

    var logs =
        logsOf(
            () -> {
              scheduled.run();
              scheduled.run();
            });

    assertThat(logs).hasSize(2);
  }

  @Test
  void theBlockThatStoppedARunIsNotAnnouncedAgainByTheNextTick() {
    var blocked = new PncpBlockedException(429, UNTIL, null);
    when(useCase.sync(6)).thenThrow(new IncrementalSyncFailedException(6, 1, blocked));

    var logs =
        logsOf(
            () -> {
              scheduled.run();
              when(useCase.blockedUntil()).thenReturn(Optional.of(UNTIL));
              scheduled.run();
            });

    assertThat(logs).hasSize(1);
  }
}
