package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.EmailAlreadyRegisteredException;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyMismatchException;
import br.dev.miguelsanzbr.radar.alerts.application.SavedSearchNotFoundException;
import br.dev.miguelsanzbr.radar.alerts.application.UnauthenticatedException;
import br.dev.miguelsanzbr.radar.alerts.domain.InvalidInputException;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearchLimitExceededException;
import br.dev.miguelsanzbr.radar.alerts.domain.SubscriberErasedException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * RFC 9457 problems for the alerts endpoints, with the type pointing to docs/problems as the
 * contract promises. Only this context's own exceptions and the request-binding errors of its
 * endpoints are mapped; anything else keeps the framework's default handling.
 */
@RestControllerAdvice(basePackageClasses = ProblemAdvice.class)
class ProblemAdvice {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<ProblemDetail> unauthenticated(UnauthenticatedException e) {
    return problem(e, HttpStatus.UNAUTHORIZED, "unauthorized", "Não autenticado");
  }

  @ExceptionHandler(SubscriberErasedException.class)
  ResponseEntity<ProblemDetail> erased(SubscriberErasedException e) {
    return problem(e, HttpStatus.FORBIDDEN, "forbidden", "Acesso negado");
  }

  @ExceptionHandler(SavedSearchNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(SavedSearchNotFoundException e) {
    return problem(e, HttpStatus.NOT_FOUND, "not-found", "Não encontrado");
  }

  @ExceptionHandler(EmailAlreadyRegisteredException.class)
  ResponseEntity<ProblemDetail> conflict(EmailAlreadyRegisteredException e) {
    return problem(e, HttpStatus.CONFLICT, "conflict", "Conflito");
  }

  @ExceptionHandler(IdempotencyMismatchException.class)
  ResponseEntity<ProblemDetail> mismatch(IdempotencyMismatchException e) {
    return problem(
        e,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "idempotency-mismatch",
        "Chave de idempotência reutilizada");
  }

  @ExceptionHandler({SavedSearchLimitExceededException.class, InvalidInputException.class})
  ResponseEntity<ProblemDetail> businessRule(RuntimeException e) {
    return problem(e, HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Requisição inválida");
  }

  @ExceptionHandler({
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    ConstraintViolationException.class
  })
  ResponseEntity<ProblemDetail> badRequest() {
    // Generic on purpose: framework messages can name internal types.
    return problem(
        "Um ou mais parâmetros são inválidos.",
        HttpStatus.BAD_REQUEST,
        "validation",
        "Requisição inválida");
  }

  private static ResponseEntity<ProblemDetail> problem(
      Exception e, HttpStatus status, String code, String title) {
    return problem(e.getMessage(), status, code, title);
  }

  private static ResponseEntity<ProblemDetail> problem(
      String detail, HttpStatus status, String code, String title) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(DOCS + code + ".md"));
    problem.setTitle(title);
    return ResponseEntity.status(status).body(problem);
  }
}
