package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

/** The identity provider has not verified the account's e-mail, so it cannot receive alerts. */
class EmailNotVerifiedException extends RuntimeException {

  EmailNotVerifiedException() {
    super("e-mail not verified by the identity provider");
  }
}
