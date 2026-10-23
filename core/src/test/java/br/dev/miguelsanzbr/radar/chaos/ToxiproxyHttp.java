package br.dev.miguelsanzbr.radar.chaos;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;

/**
 * The one call the Java client cannot make: it creates a toxic at full toxicity first and lowers it
 * afterwards, and a toxic at 100% for a moment is not a 5% fault.
 */
final class ToxiproxyHttp {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private ToxiproxyHttp() {}

  /** Adds a toxic that applies to {@code toxicity} of the connections it meets. */
  static void addToxic(
      String proxy, String name, String type, String stream, double toxicity, String attributes)
      throws IOException, InterruptedException {
    var body =
        """
        {"name":"%s","type":"%s","stream":"%s","toxicity":%s,"attributes":%s}
        """
            .formatted(name, type, stream, toxicity, attributes);
    send(HttpRequest.newBuilder(toxics(proxy)).POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  static void removeToxic(String proxy, String name) throws IOException, InterruptedException {
    send(HttpRequest.newBuilder(URI.create(toxics(proxy) + "/" + name)).DELETE());
  }

  /** For cleanup: a toxic that is already gone is not an error. */
  static void removeQuietly(String proxy, String name) {
    try {
      removeToxic(proxy, name);
    } catch (IOException e) {
      // already removed
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static URI toxics(String proxy) {
    return URI.create(
        "http://%s:%d/proxies/%s/toxics"
            .formatted(
                ChaosEnvironment.TOXIPROXY.getHost(),
                ChaosEnvironment.TOXIPROXY.getControlPort(),
                proxy));
  }

  private static void send(HttpRequest.Builder request) throws IOException, InterruptedException {
    var response = HTTP.send(request.build(), BodyHandlers.ofString());
    if (response.statusCode() >= 300) {
      throw new IOException("toxiproxy answered " + response.statusCode() + ": " + response.body());
    }
  }
}
