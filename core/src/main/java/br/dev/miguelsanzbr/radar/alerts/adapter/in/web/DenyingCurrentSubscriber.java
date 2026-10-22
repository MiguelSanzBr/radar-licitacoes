package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.UnauthenticatedException;
import br.dev.miguelsanzbr.radar.alerts.application.port.CurrentSubscriber;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Outside dev and test nobody is authenticated until the JWT resolver arrives. */
@Component
@Profile("!dev & !test")
class DenyingCurrentSubscriber implements CurrentSubscriber {

  @Override
  public UUID require() {
    throw new UnauthenticatedException();
  }
}
