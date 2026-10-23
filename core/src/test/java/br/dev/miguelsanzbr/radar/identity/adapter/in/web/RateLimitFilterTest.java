package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.identity.application.InvalidApiKeyException;
import br.dev.miguelsanzbr.radar.identity.application.RateLimitUseCase;
import br.dev.miguelsanzbr.radar.identity.domain.Decision;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";
  private static final Decision ALLOWED = new Decision(true, 30, 29, 2, 0);

  private final RateLimitUseCase use = mock(RateLimitUseCase.class);
  private final RateLimitFilter filter = new RateLimitFilter(use, false);
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final AtomicInteger chained = new AtomicInteger();
  private final FilterChain chain = (req, res) -> chained.incrementAndGet();

  private static MockHttpServletRequest request(String method, String uri) {
    var request = new MockHttpServletRequest(method, uri);
    request.setRemoteAddr("203.0.113.5");
    return request;
  }

  @ParameterizedTest
  @CsvSource({
    "/api/v1/procurements",
    "/api/v1/procurements/12345678000190-1-000123-2026",
    "/api/v1/procurements/12345678000190-1-000123-2026/history",
    "/api/v1/stats",
    "/api/v1/organs"
  })
  void everyPublicReadIsMeteredByTheAddressOfTheCaller(String uri) throws Exception {
    when(use.check(null, "203.0.113.5")).thenReturn(Optional.of(ALLOWED));

    filter.doFilter(request("GET", uri), response, chain);

    assertThat(chained).hasValue(1);
    assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("30");
    assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("29");
    assertThat(response.getHeader("RateLimit-Reset")).isEqualTo("2");
    assertThat(response.getHeader("Retry-After")).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "POST, /api/v1/procurements",
    "DELETE, /api/v1/procurements/x",
    "GET, /api/v1/saved-searches/x",
    "GET, /api/v1/me",
    "POST, /api/v1/api-keys",
    "GET, /ops/health-panel",
    "GET, /actuator/health",
    "GET, /assinatura",
    "GET, /api/v1/unknown"
  })
  void everythingElseIsLeftAlone(String method, String uri) throws Exception {
    var request = request(method, uri);
    request.addHeader("X-Api-Key", "rk_whatever");

    filter.doFilter(request, response, chain);

    assertThat(chained).hasValue(1);
    verifyNoInteractions(use);
    assertThat(response.getHeader("RateLimit-Limit")).isNull();
  }

  @Test
  void anExhaustedBucketAnswers429WithRetryAfterAndAProblem() throws Exception {
    when(use.check(null, "203.0.113.5")).thenReturn(Optional.of(new Decision(false, 30, 0, 60, 4)));

    filter.doFilter(request("GET", "/api/v1/stats"), response, chain);

    assertThat(chained).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("4");
    assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("30");
    assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("0");
    assertThat(response.getHeader("RateLimit-Reset")).isEqualTo("60");
    assertThat(response.getContentType()).startsWith("application/problem+json");
    var body = new JsonMapper().readTree(response.getContentAsString());
    assertThat(body.get("type").asString()).isEqualTo(DOCS + "rate-limited.md");
    assertThat(body.get("status").asInt()).isEqualTo(429);
    assertThat(body.get("title").asString()).isEqualTo("Limite excedido");
    assertThat(body.get("detail").asString()).contains("4 s");
  }

  @Test
  void theApiKeyHeaderIsHandedToTheUseCase() throws Exception {
    var request = request("GET", "/api/v1/stats");
    request.addHeader("X-Api-Key", "rk_abcd1234_secret");
    when(use.check("rk_abcd1234_secret", "203.0.113.5")).thenReturn(Optional.of(ALLOWED));

    filter.doFilter(request, response, chain);

    assertThat(chained).hasValue(1);
  }

  @Test
  void anUnknownOrRevokedKeyIsA401ProblemAndTheRequestStops() throws Exception {
    var request = request("GET", "/api/v1/stats");
    request.addHeader("X-Api-Key", "rk_dead");
    when(use.check("rk_dead", "203.0.113.5")).thenThrow(new InvalidApiKeyException());

    filter.doFilter(request, response, chain);

    assertThat(chained).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    var body = new JsonMapper().readTree(response.getContentAsString());
    assertThat(body.get("type").asString()).isEqualTo(DOCS + "unauthorized.md");
    assertThat(body.get("detail").asString()).contains("Chave de API");
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("ApiKey");
  }

  @Test
  void whenTheLimiterIsDownTheRequestPassesWithoutRateLimitHeaders() throws Exception {
    when(use.check(null, "203.0.113.5")).thenReturn(Optional.empty());

    filter.doFilter(request("GET", "/api/v1/stats"), response, chain);

    assertThat(chained).hasValue(1);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("RateLimit-Limit")).isNull();
  }

  @Test
  void forwardedForIsIgnoredUnlessTheProxyIsTrusted() throws Exception {
    var request = request("GET", "/api/v1/stats");
    request.addHeader("X-Forwarded-For", "198.51.100.9");
    when(use.check(null, "203.0.113.5")).thenReturn(Optional.of(ALLOWED));

    filter.doFilter(request, response, chain);

    verify(use).check(null, "203.0.113.5");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "198.51.100.9|198.51.100.9",
        "6.6.6.6, 198.51.100.9|198.51.100.9",
        "6.6.6.6,198.51.100.9 |198.51.100.9",
        "6.6.6.6,|203.0.113.5",
        "6.6.6.6, not-an-ip|203.0.113.5",
        "6.6.6.6, evil.example.com|203.0.113.5",
        "6.6.6.6, 2001:db8:1:2:aaaa:bbbb:cccc:dddd|2001:db8:1:2::/64",
        "6.6.6.6, ::ffff:198.51.100.9|198.51.100.9",
        "''|203.0.113.5"
      })
  void behindATrustedProxyTheAddressIsTheLastOneItAppended(String header, String expected)
      throws Exception {
    var trusting = new RateLimitFilter(use, true);
    var request = request("GET", "/api/v1/stats");
    request.addHeader("X-Forwarded-For", header);
    when(use.check(null, expected)).thenReturn(Optional.of(ALLOWED));

    trusting.doFilter(request, response, chain);

    verify(use).check(null, expected);
  }

  @Test
  void behindATrustedProxyTheLastHeaderLineIsTheOneTheProxyAdded() throws Exception {
    var trusting = new RateLimitFilter(use, true);
    var request = request("GET", "/api/v1/stats");
    request.addHeader("X-Forwarded-For", "6.6.6.6");
    request.addHeader("X-Forwarded-For", "198.51.100.9");
    when(use.check(null, "198.51.100.9")).thenReturn(Optional.of(ALLOWED));

    trusting.doFilter(request, response, chain);

    verify(use).check(null, "198.51.100.9");
  }

  @Test
  void withoutTheHeaderTheTrustedProxySettingFallsBackToTheRemoteAddress() throws Exception {
    var trusting = new RateLimitFilter(use, true);
    when(use.check(null, "203.0.113.5")).thenReturn(Optional.of(ALLOWED));

    trusting.doFilter(request("GET", "/api/v1/stats"), response, chain);

    verify(use).check(null, "203.0.113.5");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "2001:db8:1:2:aaaa:bbbb:cccc:dddd|2001:db8:1:2::/64",
        "2001:db8:1:2::1|2001:db8:1:2::/64",
        "::ffff:203.0.113.7|203.0.113.7",
        "0:0:0:0:0:0:0:1|0:0:0:0::/64"
      })
  void theRemoteAddressIsNormalizedToo(String remote, String expected) throws Exception {
    var request = request("GET", "/api/v1/stats");
    request.setRemoteAddr(remote);
    when(use.check(null, expected)).thenReturn(Optional.of(ALLOWED));

    filter.doFilter(request, response, chain);

    verify(use).check(null, expected);
  }
}
