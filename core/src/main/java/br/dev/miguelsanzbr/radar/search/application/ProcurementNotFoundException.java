package br.dev.miguelsanzbr.radar.search.application;

/** No procurement is projected under the given id. */
public class ProcurementNotFoundException extends RuntimeException {

  public ProcurementNotFoundException(String id) {
    super("procurement [" + id + "] not found");
  }
}
