package br.dev.miguelsanzbr.radar.identity.domain;

public class ApiKeyLimitExceededException extends RuntimeException {

  public ApiKeyLimitExceededException(int max) {
    super("Cada conta pode ter no máximo " + max + " chaves de API ativas.");
  }
}
