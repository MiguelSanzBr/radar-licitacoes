package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls the REST API of the registry the way the script and an operator would. */
public final class RegistryHttp {

  /** Status and body of one call. */
  public record Response(int status, String body) {}

  private final HttpClient client = HttpClient.newHttpClient();
  private final String base;

  public RegistryHttp(String base) {
    this.base = base;
  }

  public Response call(String method, String path, String json) {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json");
    var request =
        builder
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json))
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
