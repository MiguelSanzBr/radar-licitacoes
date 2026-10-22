package br.dev.miguelsanzbr.radar.alerts.application.port;

public interface VerificationMailer {

  void send(String email, String rawToken);
}
