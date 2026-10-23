package br.dev.miguelsanzbr.radar.ops.adapter.in.web;

import br.dev.miguelsanzbr.radar.ops.application.DlqNotFoundException;
import br.dev.miguelsanzbr.radar.ops.application.ReprocessInterruptedException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Problems of the ops endpoints of this context; binding errors answer as problem+json too. */
@RestControllerAdvice(basePackageClasses = OpsProblemAdvice.class)
class OpsProblemAdvice extends ResponseEntityExceptionHandler {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";

  @ExceptionHandler(DlqNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(DlqNotFoundException e) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    problem.setType(URI.create(DOCS + "not-found.md"));
    problem.setTitle("Não encontrado");
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
  }

  /**
   * The broker refused a message: nothing after it was tried, and the count says how far it got.
   */
  @ExceptionHandler(ReprocessInterruptedException.class)
  ResponseEntity<ProblemDetail> interrupted(ReprocessInterruptedException e) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "O reenvio parou na primeira falha ("
                + e.getMessage()
                + "); "
                + e.done()
                + " reenviada(s) antes dela.");
    problem.setType(URI.create(DOCS + "reprocess-interrupted.md"));
    problem.setTitle("Reprocessamento interrompido");
    problem.setProperty("count", e.done());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
  }
}
