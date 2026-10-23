package br.dev.miguelsanzbr.radar.ops.application;

public class DlqNotFoundException extends RuntimeException {

  public DlqNotFoundException(String message) {
    super(message);
  }
}
