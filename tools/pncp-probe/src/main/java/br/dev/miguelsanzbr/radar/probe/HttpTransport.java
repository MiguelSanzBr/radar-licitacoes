package br.dev.miguelsanzbr.radar.probe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Real transport over java.net.http. Every failure becomes a Reply with status -1. */
public final class HttpTransport implements Transport {

  static final String USER_AGENT =
      "radar-licitacoes-probe/0.1 (+https://github.com/miguelsanzbr/radar-licitacoes;"
          + " contato: miguelsanzb.p@gmail.com)";

  private final HttpClient client;
  private final Duration requestTimeout;
  private final Duration wallClockCap;

  /**
   * The JDK request timeout does not bound a body that stalls after the headers, so the whole
   * exchange, body read included, is also capped by wall clock.
   */
  public HttpTransport(Duration connectTimeout, Duration requestTimeout, Duration wallClockCap) {
    this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    this.requestTimeout = requestTimeout;
    this.wallClockCap = wallClockCap;
  }

  public static HttpTransport standard() {
    return new HttpTransport(
        Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(40));
  }

  @Override
  public Reply send(String url) {
    long start = System.nanoTime();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(requestTimeout)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    CompletableFuture<HttpResponse<byte[]>> call =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    try {
      HttpResponse<byte[]> response = call.get(wallClockCap.toMillis(), TimeUnit.MILLISECONDS);
      byte[] body = response.body();
      return new Reply(
          response.statusCode(),
          response.headers().map(),
          new String(body, StandardCharsets.UTF_8),
          body.length,
          since(start),
          null);
    } catch (ExecutionException e) {
      return failure(e.getCause().toString(), start);
    } catch (TimeoutException e) {
      call.cancel(true);
      return failure(
          "java.util.concurrent.TimeoutException: no complete response within " + wallClockCap,
          start);
    } catch (InterruptedException e) {
      call.cancel(true);
      Thread.currentThread().interrupt();
      return failure(e.toString(), start);
    }
  }

  private static Reply failure(String error, long start) {
    return new Reply(-1, Map.<String, List<String>>of(), "", 0, since(start), error);
  }

  private static long since(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
  }
}
