package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sits in front of the bearer token filter. When the token cannot be checked because the identity
 * provider's keys are unreachable, Spring Security rethrows the failure (the token was not judged,
 * so it is not a 401) and the container would answer 500. Here it is a 503 problem the client can
 * retry. The detail is fixed: the cause names an address of ours and stays in the log.
 */
public class KeysUnavailableFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(KeysUnavailableFilter.class);
  private static final Duration WARN_EVERY = Duration.ofMinutes(1);

  private final Clock clock;
  private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.MIN);

  public KeysUnavailableFilter() {
    this(Clock.systemUTC());
  }

  KeysUnavailableFilter(Clock clock) {
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (AuthenticationServiceException e) {
      // an outage fails every request that carries a token: one WARN a minute, the rest at DEBUG
      var now = clock.instant();
      if (now.isBefore(lastWarning.get().plus(WARN_EVERY))) {
        LOG.debug("token not checked, identity provider unreachable: {}", e.getMessage());
      } else {
        // two requests racing may both warn: harmless
        lastWarning.set(now);
        LOG.warn("token not checked, identity provider unreachable: {}", e.getMessage());
      }
      response.setHeader("Retry-After", "5");
      Problems.write(
          response,
          503,
          "unavailable",
          "Serviço indisponível",
          "Não foi possível validar o token agora. Tente novamente em instantes.");
    }
  }
}
