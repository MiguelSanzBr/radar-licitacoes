package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.PartitionMaintenance;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ScheduledPartitionMaintenanceTest {

  private final PartitionMaintenance maintenance = mock(PartitionMaintenance.class);
  private final ScheduledPartitionMaintenance scheduled =
      new ScheduledPartitionMaintenance(maintenance);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger =
      (Logger) LoggerFactory.getLogger(ScheduledPartitionMaintenance.class);

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  private List<Level> levels() {
    return logs.list.stream().map(ILoggingEvent::getLevel).toList();
  }

  @Test
  void theDailyRunEnsuresThePartitionsAndLogsTheHorizon() {
    when(maintenance.monthsAhead()).thenReturn(3);

    scheduled.run();

    verify(maintenance).ensure();
    assertThat(levels()).containsExactly(Level.INFO);
    assertThat(logs.list.getFirst().getFormattedMessage()).contains("3 months ahead");
  }

  @Test
  void aFailureIsLoggedAsAnErrorAndTheNextDayStillRuns() {
    org.mockito.Mockito.doThrow(new IllegalStateException("permission denied"))
        .when(maintenance)
        .ensure();

    assertThatCode(scheduled::run).doesNotThrowAnyException();

    assertThat(levels()).containsExactly(Level.ERROR);
  }

  @Test
  void startupEnsuresThePartitionsBeforeCheckingThem() {
    when(maintenance.monthsAhead()).thenReturn(3);

    scheduled.checkOnStartup();

    var order = inOrder(maintenance);
    order.verify(maintenance).ensure();
    order.verify(maintenance).monthsAhead();
    assertThat(levels()).containsExactly(Level.INFO);
  }

  @Test
  void startupWhereEnsureFailsLogsAnErrorAndDoesNotFailTheBoot() {
    doThrow(new IllegalStateException("permission denied")).when(maintenance).ensure();

    assertThatCode(scheduled::checkOnStartup).doesNotThrowAnyException();

    assertThat(levels()).containsExactly(Level.ERROR);
  }

  @Test
  void startupWithAtLeastOneMonthAheadStaysQuiet() {
    when(maintenance.monthsAhead()).thenReturn(1);

    scheduled.checkOnStartup();

    assertThat(levels()).containsExactly(Level.INFO);
  }

  @Test
  void startupWithoutTheNextMonthLogsAnError() {
    when(maintenance.monthsAhead()).thenReturn(0);

    scheduled.checkOnStartup();

    assertThat(levels()).containsExactly(Level.ERROR);
    assertThat(logs.list.getFirst().getFormattedMessage())
        .contains("next month")
        .contains("ensure_partition");
  }

  @Test
  void startupWithAnUnreachableDatabaseLogsAnErrorAndDoesNotFailTheBoot() {
    when(maintenance.monthsAhead()).thenThrow(new IllegalStateException("db down"));

    assertThatCode(scheduled::checkOnStartup).doesNotThrowAnyException();

    assertThat(levels()).containsExactly(Level.ERROR);
  }
}
