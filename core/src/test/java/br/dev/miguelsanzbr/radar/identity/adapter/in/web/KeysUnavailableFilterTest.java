package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.MutableClock;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;

class KeysUnavailableFilterTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
  private final KeysUnavailableFilter filter = new KeysUnavailableFilter(clock);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(KeysUnavailableFilter.class);
  private Level before;

  @BeforeEach
  void capture() {
    before = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void release() {
    logger.detachAppender(logs);
    logger.setLevel(before);
  }

  private MockHttpServletResponse fail() throws Exception {
    FilterChain failing =
        (request, response) -> {
          throw new AuthenticationServiceException("keys at http://idp.internal unreachable");
        };
    var response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, failing);
    return response;
  }

  @Test
  void anUncheckedTokenIsAFixedRetryable503() throws Exception {
    var response = fail();

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("unavailable")
        .doesNotContain("idp.internal");
  }

  @Test
  void aPassingRequestIsLeftAlone() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/me"),
        response,
        (request, answer) -> ((MockHttpServletResponse) answer).setStatus(204));

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(logs.list).isEmpty();
  }

  @Test
  void theWarningIsLoggedOnceAMinuteAndTheRestGoToDebug() throws Exception {
    fail();
    fail();
    clock.advance(Duration.ofSeconds(59));
    fail();
    clock.advance(Duration.ofSeconds(2));
    fail();

    assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(2);
    assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.DEBUG)).hasSize(2);
  }
}
