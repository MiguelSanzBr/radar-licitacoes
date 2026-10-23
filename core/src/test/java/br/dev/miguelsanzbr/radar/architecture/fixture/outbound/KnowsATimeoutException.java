package br.dev.miguelsanzbr.radar.architecture.fixture.outbound;

import java.net.http.HttpTimeoutException;

/** Tells a network failure apart by type, which is no client and stays allowed. */
public class KnowsATimeoutException {

  boolean isTimeout(Throwable t) {
    return t instanceof HttpTimeoutException;
  }
}
