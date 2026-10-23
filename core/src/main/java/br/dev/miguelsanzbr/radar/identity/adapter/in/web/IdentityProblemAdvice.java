package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import br.dev.miguelsanzbr.radar.identity.application.ApiKeyNotFoundException;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKeyLimitExceededException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problems of the API key endpoints, typed as the contract promises. */
@RestControllerAdvice(basePackageClasses = IdentityProblemAdvice.class)
class IdentityProblemAdvice {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";

  @ExceptionHandler(ApiKeyLimitExceededException.class)
  ResponseEntity<ProblemDetail> limit(ApiKeyLimitExceededException e) {
    return problem(e, HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Requisição inválida");
  }

  @ExceptionHandler(ApiKeyNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(ApiKeyNotFoundException e) {
    return problem(e, HttpStatus.NOT_FOUND, "not-found", "Não encontrado");
  }

  private static ResponseEntity<ProblemDetail> problem(
      RuntimeException e, HttpStatus status, String code, String title) {
    var problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
    problem.setType(URI.create(DOCS + code + ".md"));
    problem.setTitle(title);
    return ResponseEntity.status(status).body(problem);
  }
}
