package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import br.dev.miguelsanzbr.radar.identity.application.InvalidApiKeyException;
import br.dev.miguelsanzbr.radar.identity.application.RateLimitUseCase;
import br.dev.miguelsanzbr.radar.identity.domain.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Meters the public reads after the security chain has let them through. The caller is the API key
 * of {@code X-Api-Key} when there is one (an unknown or revoked key is a 401), else its address.
 * The key only picks the bucket: it never authenticates anything, and the routes that need a role
 * do not pass through here at all.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  /** The {@code GET} routes anyone may read; the security chain and this filter share the list. */
  public static final List<String> PUBLIC_READS =
      List.of("/api/v1/procurements/**", "/api/v1/stats", "/api/v1/organs");

  private static final RequestMatcher PUBLIC_READ =
      new OrRequestMatcher(
          PUBLIC_READS.stream()
              .map(
                  pattern ->
                      (RequestMatcher)
                          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern))
              .toList());

  private final RateLimitUseCase rateLimit;
  private final boolean trustForwarded;

  /**
   * @param trustForwarded whether one proxy we control sits in front (see {@link ClientAddress})
   */
  public RateLimitFilter(RateLimitUseCase rateLimit, boolean trustForwarded) {
    this.rateLimit = rateLimit;
    this.trustForwarded = trustForwarded;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !PUBLIC_READ.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<Decision> verdict;
    try {
      verdict =
          rateLimit.check(
              request.getHeader("X-Api-Key"), ClientAddress.of(request, trustForwarded));
    } catch (InvalidApiKeyException e) {
      response.setHeader("WWW-Authenticate", "ApiKey");
      Problems.write(response, 401, "unauthorized", "Não autenticado", e.getMessage());
      return;
    }
    if (verdict.isPresent()) {
      var decision = verdict.get();
      response.setIntHeader("RateLimit-Limit", decision.limit());
      response.setIntHeader("RateLimit-Remaining", decision.remaining());
      response.setIntHeader("RateLimit-Reset", decision.resetSeconds());
      if (!decision.allowed()) {
        response.setIntHeader("Retry-After", decision.retryAfterSeconds());
        Problems.write(
            response,
            429,
            "rate-limited",
            "Limite excedido",
            "Tente novamente em " + decision.retryAfterSeconds() + " s.");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
