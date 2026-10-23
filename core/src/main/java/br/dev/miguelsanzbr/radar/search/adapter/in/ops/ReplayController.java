package br.dev.miguelsanzbr.radar.search.adapter.in.ops;

import br.dev.miguelsanzbr.radar.search.application.ReplayReport;
import br.dev.miguelsanzbr.radar.search.application.ReplayUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator entry point of the read model rebuild: accepts the request, runs it in the background
 * and reports the state of the latest run. One replay at a time per instance.
 */
@RestController
@RequestMapping("/ops/replay")
class ReplayController {

  private static final Logger LOG = LoggerFactory.getLogger(ReplayController.class);

  enum Status {
    RUNNING,
    FAILED,
    DONE
  }

  record Accepted(String status) {}

  /** The latest run: {@code error} only when FAILED, {@code report} only when DONE. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Latest(Status status, String error, ReplayReport report) {}

  private final ReplayUseCase useCase;
  private final AtomicReference<Latest> latest = new AtomicReference<>();

  ReplayController(ReplayUseCase useCase) {
    this.useCase = useCase;
  }

  @PostMapping
  synchronized ResponseEntity<Object> replay() {
    var previous = latest.get();
    if (previous != null && previous.status() == Status.RUNNING) {
      var status = HttpStatus.CONFLICT;
      return ResponseEntity.status(status)
          .body(ProblemDetail.forStatusAndDetail(status, "a replay is already running"));
    }
    // why: RUNNING is set before the response, so a poll never sees the previous run's result
    latest.set(new Latest(Status.RUNNING, null, null));
    Thread.ofVirtual().start(this::execute);
    return ResponseEntity.accepted().body(new Accepted("ACCEPTED"));
  }

  @GetMapping("/last")
  ResponseEntity<Latest> last() {
    var current = latest.get();
    return current == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(current);
  }

  /** Whatever ends the run, the state leaves RUNNING; an Error is still rethrown after that. */
  private void execute() {
    try {
      var report = useCase.replay();
      LOG.info(
          "replay read {} events in {} ({} events/s)",
          report.events(),
          report.duration(),
          Math.round(report.eventsPerSecond()));
      latest.set(new Latest(Status.DONE, null, report));
    } catch (Throwable failure) {
      LOG.error("replay failed", failure);
      var message = failure.getMessage();
      var error = message == null ? failure.getClass().getSimpleName() : message;
      latest.set(new Latest(Status.FAILED, error, null));
      if (failure instanceof Error fatal) {
        throw fatal;
      }
    }
  }
}
