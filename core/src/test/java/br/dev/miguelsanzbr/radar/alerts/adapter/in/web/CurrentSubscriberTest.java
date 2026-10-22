package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.alerts.application.UnauthenticatedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CurrentSubscriberTest {

  @Test
  void headerCarriesTheSubscriberId() {
    var id = UUID.randomUUID();
    var request = new MockHttpServletRequest();
    request.addHeader("X-Debug-Subscriber", id.toString());

    assertThat(new HeaderCurrentSubscriber(request).require()).isEqualTo(id);
  }

  @Test
  void missingOrMalformedHeaderIsUnauthenticated() {
    var request = new MockHttpServletRequest();
    var current = new HeaderCurrentSubscriber(request);
    assertThatThrownBy(current::require).isInstanceOf(UnauthenticatedException.class);

    request.addHeader("X-Debug-Subscriber", "not-a-uuid");
    assertThatThrownBy(current::require).isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void withoutTheDebugProfileNobodyIsAuthenticated() {
    assertThatThrownBy(() -> new DenyingCurrentSubscriber().require())
        .isInstanceOf(UnauthenticatedException.class);
  }
}
