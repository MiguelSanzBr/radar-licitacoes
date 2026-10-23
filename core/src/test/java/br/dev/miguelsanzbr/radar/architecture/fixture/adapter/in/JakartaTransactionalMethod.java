package br.dev.miguelsanzbr.radar.architecture.fixture.adapter.in;

import jakarta.transaction.Transactional;

/** Violates the rule at method level (Jakarta). */
public class JakartaTransactionalMethod {

  @Transactional
  public void handle() {}
}
