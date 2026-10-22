package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.ConsentRequiredException;
import br.dev.miguelsanzbr.radar.alerts.application.UnauthenticatedException;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtCurrentSubscriberTest {

  private static final UUID ID = UUID.fromString("0198a3a0-0000-7000-8000-000000000001");

  private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
  private final JwtCurrentSubscriber current = new JwtCurrentSubscriber(subscribers);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static Jwt jwt(String subject) {
    return Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
  }

  @Test
  void bearerTokenSubjectResolvesTheLinkedSubscriber() {
    when(subscribers.findIdBySubject("kc-1")).thenReturn(Optional.of(ID));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("kc-1")));

    assertThat(current.require()).isEqualTo(ID);
  }

  @Test
  void browserSessionSubjectResolvesTheLinkedSubscriber() {
    when(subscribers.findIdBySubject("kc-2")).thenReturn(Optional.of(ID));
    var idToken =
        new OidcIdToken(
            "t", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), java.util.Map.of("sub", "kc-2"));
    var user = new DefaultOidcUser(List.of(), idToken);
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(user, List.of(), "keycloak"));

    assertThat(current.require()).isEqualTo(ID);
  }

  @Test
  void subjectWithoutSubscriberMustGiveConsentFirst() {
    when(subscribers.findIdBySubject("kc-1")).thenReturn(Optional.empty());
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("kc-1")));

    assertThatThrownBy(current::require).isInstanceOf(ConsentRequiredException.class);
  }

  @Test
  void anonymousOrMissingAuthenticationIsUnauthenticated() {
    assertThatThrownBy(current::require).isInstanceOf(UnauthenticatedException.class);

    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertThatThrownBy(current::require).isInstanceOf(UnauthenticatedException.class);
  }
}
