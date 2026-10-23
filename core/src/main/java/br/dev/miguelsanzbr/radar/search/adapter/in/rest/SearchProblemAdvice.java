package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import br.dev.miguelsanzbr.radar.search.application.ProcurementNotFoundException;
import br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException;
import br.dev.miguelsanzbr.radar.search.domain.InvalidCursorException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problems of the search routes, typed as the contract promises. The framework's own
 * rejections (406, 405, 404 of a path) keep their status through the base class; anything
 * unexpected is a fixed 500 whose cause only the log shows.
 */
@RestControllerAdvice(basePackageClasses = SearchProblemAdvice.class)
class SearchProblemAdvice extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(SearchProblemAdvice.class);
  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";
  private static final String INVALID = "Um ou mais parâmetros são inválidos.";

  @ExceptionHandler(InvalidCursorException.class)
  ResponseEntity<ProblemDetail> cursor() {
    return respond(
        problem(
            HttpStatus.BAD_REQUEST,
            "invalid-cursor",
            "Cursor inválido",
            "O cursor não pôde ser decodificado."));
  }

  @ExceptionHandler(ProcurementNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound() {
    return respond(
        problem(HttpStatus.NOT_FOUND, "not-found", "Não encontrado", "Licitação inexistente."));
  }

  @ExceptionHandler(ReadTimeoutException.class)
  ResponseEntity<ProblemDetail> timeout() {
    var problem =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "unavailable",
            "Serviço indisponível",
            "A consulta demorou demais. Tente novamente em instantes.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "5")
        .body(problem);
  }

  @ExceptionHandler(ValidationFailure.class)
  ResponseEntity<ProblemDetail> failure(ValidationFailure e) {
    return respond(validation(e.status(), List.of(error(e.field(), e.getMessage()))));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> mismatch(MethodArgumentTypeMismatchException e) {
    return respond(
        validation(HttpStatus.BAD_REQUEST, List.of(error(e.getName(), "valor inválido"))));
  }

  /**
   * The path of a violation is {@code method.parameter}; the contract names the parameter. The
   * message is already Portuguese: {@link FixedLocaleConfiguration} sets the locale before the
   * validation runs.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> constraints(ConstraintViolationException e) {
    return respond(
        validation(
            HttpStatus.BAD_REQUEST,
            e.getConstraintViolations().stream()
                .map(
                    violation -> {
                      var path = violation.getPropertyPath().toString();
                      return error(
                          path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
                    })
                .toList()));
  }

  @Override
  protected ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.badRequest()
        .body(
            validation(
                HttpStatus.BAD_REQUEST,
                List.of(error(e.getParameterName(), "parâmetro obrigatório"))));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e) {
    LOG.error("unexpected failure on a search route", e);
    return respond(
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Erro inesperado",
            "Erro inesperado. Tente novamente mais tarde."));
  }

  private static Map<String, String> error(String field, String message) {
    return Map.of("field", field, "message", message);
  }

  private static ProblemDetail validation(HttpStatus status, List<Map<String, String>> errors) {
    var problem = problem(status, "validation", "Requisição inválida", INVALID);
    problem.setProperty("errors", errors);
    return problem;
  }

  private static ProblemDetail problem(
      HttpStatus status, String code, String title, String detail) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(DOCS + code + ".md"));
    problem.setTitle(title);
    return problem;
  }

  private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
    return ResponseEntity.status(problem.getStatus()).body(problem);
  }
}
