package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.UnauthenticatedException;
import br.dev.miguelsanzbr.radar.alerts.application.port.CurrentSubscriber;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Reads the subscriber id from a header; only for local development and tests. */
// shortcut: replaced by JWT subject in PR 5.1
@Component
@Profile({"dev", "test"})
class HeaderCurrentSubscriber implements CurrentSubscriber {

  static final String HEADER = "X-Debug-Subscriber";

  private final HttpServletRequest request;

  HeaderCurrentSubscriber(HttpServletRequest request) {
    this.request = request;
  }

  @Override
  public UUID require() {
    try {
      return UUID.fromString(request.getHeader(HEADER));
    } catch (RuntimeException e) {
      throw new UnauthenticatedException();
    }
  }
}
