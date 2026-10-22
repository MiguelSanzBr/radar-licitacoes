package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.util.UUID;

public interface CurrentSubscriber {

  /** Id of the authenticated subscriber; throws {@code UnauthenticatedException} when absent. */
  UUID require();
}
