package br.dev.miguelsanzbr.radar.ingest.adapter.in.ops;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator entry point of the backfill: accepts the range and runs it in the background. */
@RestController
@RequestMapping("/ops")
class BackfillController {

  private static final Logger LOG = LoggerFactory.getLogger(BackfillController.class);
  private static final int MAX_DAYS = 400;

  record Accepted(boolean accepted, LocalDate from, LocalDate to, int modality) {}

  private final BackfillUseCase useCase;
  private final BackfillWindowPolicy policy;
  private final Clock clock;

  BackfillController(BackfillUseCase useCase, BackfillWindowPolicy policy, Clock clock) {
    this.useCase = useCase;
    this.policy = policy;
    this.clock = clock;
  }

  @PostMapping("/backfill")
  ResponseEntity<Object> backfill(
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "6") int modality,
      @RequestParam(defaultValue = "false") boolean force) {
    if (from.isAfter(to)) {
      return problem(HttpStatus.BAD_REQUEST, "from must not be after to");
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      return problem(HttpStatus.BAD_REQUEST, "range must not exceed " + MAX_DAYS + " days");
    }
    if (useCase.isBusy(from, to, modality)) {
      return problem(HttpStatus.CONFLICT, "a backfill of this range is already running");
    }
    if (!force && !policy.allowed(clock.instant())) {
      return problem(
          HttpStatus.CONFLICT, "outside the backfill window; repeat with force=true to run now");
    }
    Thread.ofVirtual().start(() -> execute(from, to, modality));
    return ResponseEntity.accepted().body(new Accepted(true, from, to, modality));
  }

  void execute(LocalDate from, LocalDate to, int modality) {
    try {
      useCase.run(from, to, modality);
    } catch (RuntimeException e) {
      LOG.error("backfill {} to {} (modality {}) stopped", from, to, modality, e);
    }
  }

  private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }
}
