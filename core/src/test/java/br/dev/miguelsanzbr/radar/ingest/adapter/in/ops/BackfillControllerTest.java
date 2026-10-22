package br.dev.miguelsanzbr.radar.ingest.adapter.in.ops;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillFailedException;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BackfillControllerTest {

  private static final Instant NIGHT = Instant.parse("2026-10-22T05:00:00Z");
  private static final Instant NOON = Instant.parse("2026-10-22T15:00:00Z");

  private final BackfillUseCase useCase = mock(BackfillUseCase.class);
  private final BackfillWindowPolicy policy =
      new BackfillWindowPolicy(
          LocalTime.of(0, 0), LocalTime.of(6, 0), ZoneId.of("America/Sao_Paulo"));

  private MockMvc mvc(Instant now) {
    var controller = new BackfillController(useCase, policy, Clock.fixed(now, ZoneOffset.UTC));
    return MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void acceptsAValidRangeAndRunsItOnAnotherThread() throws Exception {
    mvc(NIGHT)
        .perform(
            post("/ops/backfill")
                .param("from", "2026-10-20")
                .param("to", "2026-10-22")
                .param("modality", "12"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.from").value("2026-10-20"))
        .andExpect(jsonPath("$.to").value("2026-10-22"))
        .andExpect(jsonPath("$.modality").value(12));

    verify(useCase, timeout(2000)).run(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22), 12);
  }

  @Test
  void modalityDefaultsToSix() throws Exception {
    mvc(NIGHT)
        .perform(post("/ops/backfill").param("from", "2026-10-20").param("to", "2026-10-20"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.modality").value(6));

    verify(useCase, timeout(2000)).run(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), 6);
  }

  @Test
  void fourHundredDaysIsTheLimitInclusive() throws Exception {
    // 2026-01-01 .. 2027-02-04 is exactly 400 days
    mvc(NIGHT)
        .perform(post("/ops/backfill").param("from", "2026-01-01").param("to", "2027-02-04"))
        .andExpect(status().isAccepted());
    mvc(NIGHT)
        .perform(post("/ops/backfill").param("from", "2026-01-01").param("to", "2027-02-05"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("range must not exceed 400 days"));
  }

  @Test
  void aSingleDayIsValidAndFromAfterToIsNot() throws Exception {
    mvc(NIGHT)
        .perform(post("/ops/backfill").param("from", "2026-10-20").param("to", "2026-10-19"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("from must not be after to"));

    verify(useCase, never()).run(any(), any(), anyInt());
  }

  @Test
  void outsideTheWindowItIsAConflictUnlessForced() throws Exception {
    mvc(NOON)
        .perform(post("/ops/backfill").param("from", "2026-10-20").param("to", "2026-10-20"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409));
    verify(useCase, never()).run(any(), any(), anyInt());

    mvc(NOON)
        .perform(
            post("/ops/backfill")
                .param("from", "2026-10-20")
                .param("to", "2026-10-20")
                .param("force", "true"))
        .andExpect(status().isAccepted());
    verify(useCase, timeout(2000)).run(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), 6);
  }

  @Test
  void aFailedRunIsLoggedNotThrownOutOfTheThread() {
    var day = LocalDate.of(2026, 10, 20);
    doThrow(new BackfillFailedException(day, 3, new IllegalStateException("down")))
        .when(useCase)
        .run(day, day, 6);
    var controller = new BackfillController(useCase, policy, Clock.fixed(NIGHT, ZoneOffset.UTC));

    controller.execute(day, day, 6);

    verify(useCase).run(day, day, 6);
  }

  @Test
  void aBusyWindowIsAConflictEvenWhenForced() throws Exception {
    when(useCase.isBusy(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21), 6))
        .thenReturn(true);

    mvc(NIGHT)
        .perform(
            post("/ops/backfill")
                .param("from", "2026-10-20")
                .param("to", "2026-10-21")
                .param("force", "true"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("a backfill of this range is already running"));

    verify(useCase, never()).run(any(), any(), anyInt());
  }
}
