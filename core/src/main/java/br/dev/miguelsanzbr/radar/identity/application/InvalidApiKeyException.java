package br.dev.miguelsanzbr.radar.identity.application;

public class InvalidApiKeyException extends RuntimeException {

  public InvalidApiKeyException() {
    super("Chave de API inválida ou revogada.");
  }
}
