package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.EstimatedValueCentsBackfill;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ScheduledEstimatedValueCentsBackfillTest {

  private final EstimatedValueCentsBackfill backfill = mock(EstimatedValueCentsBackfill.class);
  private final ScheduledEstimatedValueCentsBackfill scheduled =
      new ScheduledEstimatedValueCentsBackfill(backfill);

  @Test
  void eachTickRunsTheBackfill() {
    scheduled.run();

    verify(backfill).run();
  }

  @Test
  void aRunThatFilledRowsSaysSoInTheLog() {
    when(backfill.run()).thenReturn(42L);
    var logs = new ListAppender<ILoggingEvent>();
    var logger = (Logger) LoggerFactory.getLogger(ScheduledEstimatedValueCentsBackfill.class);
    logs.start();
    logger.addAppender(logs);
    try {
      scheduled.run();
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("value cents backfill filled 42 rows");
  }

  @Test
  void aFailedRunDoesNotEscapeSoTheNextTickRetries() {
    when(backfill.run()).thenThrow(new IllegalStateException("db down"));

    assertThatCode(scheduled::run).doesNotThrowAnyException();
  }
}
