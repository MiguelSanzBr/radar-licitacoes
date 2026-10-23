package br.dev.miguelsanzbr.radar.identity.application;

public class ApiKeyNotFoundException extends RuntimeException {

  public ApiKeyNotFoundException() {
    super("Chave de API inexistente.");
  }
}
