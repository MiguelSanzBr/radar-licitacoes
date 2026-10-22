package br.dev.miguelsanzbr.radar.observability;

import br.dev.miguelsanzbr.radar.shared.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id in the log context for the whole request and returns it in the response.
 * The id comes from {@code X-Correlation-Id} when it is short and plain, else a new UUID v7.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  /** Echoed into a header and into every log line, so anything but plain tokens is refused. */
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final UuidV7 ids;

  public CorrelationIdFilter(UuidV7 ids) {
    this.ids = ids;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var incoming = request.getHeader(HEADER);
    var id =
        incoming != null && VALID.matcher(incoming).matches() ? incoming : ids.next().toString();
    response.setHeader(HEADER, id);
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
