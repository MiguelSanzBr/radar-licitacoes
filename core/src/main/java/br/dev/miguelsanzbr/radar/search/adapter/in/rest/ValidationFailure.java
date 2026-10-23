package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import org.springframework.http.HttpStatus;

/** A request parameter the contract's own constraints cannot reject; answered as a problem. */
class ValidationFailure extends RuntimeException {

  private final HttpStatus status;
  private final String field;

  ValidationFailure(HttpStatus status, String field, String message) {
    super(message);
    this.status = status;
    this.field = field;
  }

  HttpStatus status() {
    return status;
  }

  String field() {
    return field;
  }
}
