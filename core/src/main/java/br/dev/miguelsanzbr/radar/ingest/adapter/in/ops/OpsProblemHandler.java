package br.dev.miguelsanzbr.radar.ingest.adapter.in.ops;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Missing or malformed parameters of the ops endpoints answer as problem+json, not as HTML. */
@RestControllerAdvice(assignableTypes = BackfillController.class)
class OpsProblemHandler extends ResponseEntityExceptionHandler {}
