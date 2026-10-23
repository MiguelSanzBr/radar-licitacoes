package br.dev.miguelsanzbr.radar.search.adapter.in.web;

/** A value of the form the search cannot use; the message is the sentence the person reads. */
class InvalidInput extends RuntimeException {

  InvalidInput(String message) {
    super(message);
  }
}
