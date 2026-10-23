package br.dev.miguelsanzbr.radar.architecture.fixture.adapter.in;

import org.springframework.transaction.annotation.Transactional;

/** Violates the rule at method level (Spring). */
public class SpringTransactionalMethod {

  @Transactional
  public void handle() {}
}
