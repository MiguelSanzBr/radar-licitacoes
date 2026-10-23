package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import br.dev.miguelsanzbr.radar.search.application.ProcurementNotFoundException;
import br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException;
import br.dev.miguelsanzbr.radar.search.domain.InvalidCursorException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Failures of the pages as a sentence in Portuguese with a way back. Inside htmx the sentence
 * replaces the results area, because htmx would otherwise keep the old list and say nothing.
 */
@ControllerAdvice(basePackageClasses = WebAdvice.class)
class WebAdvice {

  private static final Logger LOG = LoggerFactory.getLogger(WebAdvice.class);

  @ExceptionHandler(InvalidInput.class)
  ModelAndView invalid(InvalidInput e, HttpServletRequest request, HttpServletResponse response) {
    return error(HttpStatus.BAD_REQUEST, "Pedido inválido", e.getMessage(), request, response);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ModelAndView mismatch(HttpServletRequest request, HttpServletResponse response) {
    return error(
        HttpStatus.BAD_REQUEST,
        "Pedido inválido",
        "Filtro inválido. Escolha um valor da lista.",
        request,
        response);
  }

  @ExceptionHandler(InvalidCursorException.class)
  ModelAndView cursor(HttpServletRequest request, HttpServletResponse response) {
    return error(
        HttpStatus.BAD_REQUEST,
        "Pedido inválido",
        "Esta página de resultados não vale mais. Refaça a busca.",
        request,
        response);
  }

  @ExceptionHandler(ProcurementNotFoundException.class)
  ModelAndView notFound(HttpServletRequest request, HttpServletResponse response) {
    return error(
        HttpStatus.NOT_FOUND,
        "Licitação não encontrada",
        "Não há licitação com este identificador. Ela pode ter saído da base ou o endereço está incompleto.",
        request,
        response);
  }

  @ExceptionHandler(ReadTimeoutException.class)
  ModelAndView timeout(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader(HttpHeaders.RETRY_AFTER, "5");
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Serviço indisponível",
        "A consulta demorou demais. Tente novamente em instantes.",
        request,
        response);
  }

  @ExceptionHandler(Exception.class)
  ModelAndView unexpected(Exception e, HttpServletRequest request, HttpServletResponse response) {
    LOG.error("unexpected failure on a page", e);
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Erro inesperado",
        "Erro inesperado. Tente novamente mais tarde.",
        request,
        response);
  }

  private static ModelAndView error(
      HttpStatus status,
      String title,
      String message,
      HttpServletRequest request,
      HttpServletResponse response) {
    response.setStatus(status.value());
    var view = new ModelAndView("search/error");
    if (request.getHeader("HX-Request") != null) {
      response.setHeader("HX-Retarget", "#results");
      response.setHeader("HX-Reswap", "innerHTML");
      // the failed request must not leave the address of a fragment in the history
      response.setHeader("HX-Push-Url", "false");
      view.setViewName("search/error :: message");
    }
    return view.addObject("heading", title).addObject("message", message);
  }
}
