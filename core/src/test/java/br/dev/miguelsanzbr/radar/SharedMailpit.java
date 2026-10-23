package br.dev.miguelsanzbr.radar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Mailpit per JVM (same image as infra/compose). The application reaches its SMTP port through
 * properties, never through a bean, and tests read the mailbox through the REST API.
 */
public final class SharedMailpit {

  /** A message as the Mailpit API shows it. */
  public record Mail(String id, String subject, String to, String text, String html) {}

  public static final GenericContainer<?> INSTANCE =
      new GenericContainer<>(
              DockerImageName.parse(
                  "axllent/mailpit:v1.31.6@sha256:23fe39e81bde596a918000b3135c0f85dc8bb4c9ea6be274b051143f3e300387"))
          .withExposedPorts(1025, 8025)
          .waitingFor(Wait.forHttp("/readyz").forPort(8025));

  static {
    INSTANCE.start();
  }

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SharedMailpit() {}

  public static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", INSTANCE::getHost);
    registry.add("spring.mail.port", () -> INSTANCE.getMappedPort(1025));
  }

  private static String call(String method, String path) {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://%s:%d%s"
                        .formatted(INSTANCE.getHost(), INSTANCE.getMappedPort(8025), path)))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public static void clear() {
    call("DELETE", "/api/v1/messages");
  }

  /** Every message addressed to {@code address}, oldest first. */
  public static List<Mail> messagesTo(String address) {
    var list = JSON.readTree(call("GET", "/api/v1/messages?limit=500"));
    var mails = new ArrayList<Mail>();
    for (var summary : list.get("messages")) {
      var detail = JSON.readTree(call("GET", "/api/v1/message/" + summary.get("ID").asString()));
      var to = detail.get("To").get(0).get("Address").asString();
      if (to.equalsIgnoreCase(address)) {
        mails.add(
            new Mail(
                detail.get("ID").asString(),
                detail.get("Subject").asString(),
                to,
                detail.get("Text").asString(),
                detail.get("HTML").asString()));
      }
    }
    Collections.reverse(mails);
    return mails;
  }
}
