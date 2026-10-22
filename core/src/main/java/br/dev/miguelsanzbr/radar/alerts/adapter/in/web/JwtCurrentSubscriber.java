package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.ConsentRequiredException;
import br.dev.miguelsanzbr.radar.alerts.application.UnauthenticatedException;
import br.dev.miguelsanzbr.radar.alerts.application.port.CurrentSubscriber;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.stereotype.Component;

/**
 * The subscriber linked to the token subject: the bearer JWT on the API, the OIDC ID token in a
 * browser session. The link is made only when the account gives consent on /assinatura.
 */
@Component
class JwtCurrentSubscriber implements CurrentSubscriber {

  private final SubscriberRepository subscribers;

  JwtCurrentSubscriber(SubscriberRepository subscribers) {
    this.subscribers = subscribers;
  }

  @Override
  public UUID require() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof ClaimAccessor token)) {
      throw new UnauthenticatedException();
    }
    return subscribers
        .findIdBySubject(token.getClaimAsString("sub"))
        .orElseThrow(ConsentRequiredException::new);
  }
}
