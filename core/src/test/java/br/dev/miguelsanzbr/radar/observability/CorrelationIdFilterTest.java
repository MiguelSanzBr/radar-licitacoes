package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.shared.UuidV7;
import jakarta.servlet.FilterChain;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter =
      new CorrelationIdFilter(new UuidV7(Clock.systemUTC(), new SecureRandom()));
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final AtomicReference<String> seenInChain = new AtomicReference<>();
  private final FilterChain chain =
      (req, res) -> seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

  @Test
  void aValidHeaderIsKeptAndEchoedAndVisibleInTheMdcDuringTheRequest() throws Exception {
    request.addHeader("X-Correlation-Id", "abc-123_DEF.456");

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("abc-123_DEF.456");
    assertThat(seenInChain).hasValue("abc-123_DEF.456");
  }

  @Test
  void withoutTheHeaderAUuidV7IsGeneratedAndReturned() throws Exception {
    filter.doFilter(request, response, chain);

    var generated = UUID.fromString(response.getHeader("X-Correlation-Id"));
    assertThat(generated.version()).isEqualTo(7);
    assertThat(seenInChain).hasValue(generated.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "has spaces", "line\nbreak", "{\"json\":1}", "<script>"})
  void anUnusableHeaderIsReplacedByAGeneratedId(String bad) throws Exception {
    request.addHeader("X-Correlation-Id", bad);

    filter.doFilter(request, response, chain);

    assertThat(UUID.fromString(response.getHeader("X-Correlation-Id")).version()).isEqualTo(7);
  }

  @Test
  void aTooLongHeaderIsReplacedByAGeneratedId() throws Exception {
    request.addHeader("X-Correlation-Id", "a".repeat(65));

    filter.doFilter(request, response, chain);

    assertThat(UUID.fromString(response.getHeader("X-Correlation-Id")).version()).isEqualTo(7);
  }

  @Test
  void aHeaderOfExactlyTheMaximumLengthIsKept() throws Exception {
    var max = "a".repeat(64);
    request.addHeader("X-Correlation-Id", max);

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(max);
  }

  @Test
  void theMdcIsClearedAfterTheRequestAlsoWhenTheChainFails() {
    FilterChain failing =
        (req, res) -> {
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(() -> filter.doFilter(request, response, failing))
        .isInstanceOf(IllegalStateException.class);

    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }
}
