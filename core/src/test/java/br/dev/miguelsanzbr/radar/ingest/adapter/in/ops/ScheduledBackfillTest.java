package br.dev.miguelsanzbr.radar.ingest.adapter.in.ops;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowBusyException;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduledBackfillTest {

  private static final LocalDate D19 = LocalDate.of(2026, 10, 19);
  private static final LocalDate D20 = LocalDate.of(2026, 10, 20);
  private static final LocalDate D21 = LocalDate.of(2026, 10, 21);

  private final BackfillUseCase useCase = mock(BackfillUseCase.class);
  private final BackfillWindowPolicy policy =
      new BackfillWindowPolicy(
          LocalTime.of(0, 0), LocalTime.of(6, 0), ZoneId.of("America/Sao_Paulo"));
  private final BackfillProperties properties =
      new BackfillProperties(LocalTime.of(0, 0), LocalTime.of(6, 0), true, D19, 12);

  /** A clock that answers each call with the next instant, repeating the last one. */
  private static Clock sequence(String... instants) {
    var queue = new ArrayDeque<>(List.of(instants));
    return new Clock() {
      private Instant last;

      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
      }

      @Override
      public Instant instant() {
        if (!queue.isEmpty()) {
          last = Instant.parse(queue.poll());
        }
        return last;
      }
    };
  }

  private ScheduledBackfill at(Clock clock) {
    return new ScheduledBackfill(useCase, policy, properties, clock);
  }

  private ScheduledBackfill at(String instant) {
    return at(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
  }

  @Test
  void insideTheWindowItBacksfillsEachDayFromTheConfiguredOneUntilYesterdayInSaoPaulo() {
    // 02:00 of 2026-10-22 in Sao Paulo: days 19, 20 and 21
    at("2026-10-22T05:00:00Z").run();

    var order = inOrder(useCase);
    order.verify(useCase).run(D19, D19, 12);
    order.verify(useCase).run(D20, D20, 12);
    order.verify(useCase).run(D21, D21, 12);
    verify(useCase, never()).run(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22), 12);
  }

  @Test
  void outsideTheWindowNothingRuns() {
    // 23:30 UTC on the 22nd is 20:30 in Sao Paulo
    at("2026-10-22T23:30:00Z").run();

    verify(useCase, never()).run(any(), any(), anyInt());
  }

  @Test
  void yesterdayIsTheSaoPauloDayNotTheUtcOne() {
    // 03:30 UTC on the 23rd is 00:30 of the 23rd in Sao Paulo, inside the window
    at("2026-10-23T03:30:00Z").run();

    verify(useCase).run(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22), 12);
  }

  @Test
  void daysThatAlreadyHaveAFinishedRunAreSkipped() {
    when(useCase.isDone(D19, 12)).thenReturn(true);
    when(useCase.isDone(D21, 12)).thenReturn(true);

    at("2026-10-22T05:00:00Z").run();

    verify(useCase, never()).run(D19, D19, 12);
    verify(useCase).run(D20, D20, 12);
    verify(useCase, never()).run(D21, D21, 12);
  }

  @Test
  void itStopsAsSoonAsTheWindowClosesBetweenDays() {
    // the first reading yields yesterday; then 05:58 for days 19 and 20, and 06:01 for day 21
    at(sequence(
            "2026-10-22T08:58:00Z",
            "2026-10-22T08:58:00Z",
            "2026-10-22T08:58:00Z",
            "2026-10-22T09:01:00Z"))
        .run();

    verify(useCase).run(D19, D19, 12);
    verify(useCase).run(D20, D20, 12);
    verify(useCase, never()).run(D21, D21, 12);
  }

  @Test
  void aBusyDayIsSkippedAndTheNextOneStillRuns() {
    doThrow(new BackfillWindowBusyException(D20, 12)).when(useCase).run(D20, D20, 12);

    at("2026-10-22T05:00:00Z").run();

    verify(useCase).run(D21, D21, 12);
  }
}
