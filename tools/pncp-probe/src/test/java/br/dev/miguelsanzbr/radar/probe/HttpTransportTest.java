package br.dev.miguelsanzbr.radar.probe;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

  private WireMockServer server;
  private HttpTransport transport;

  @BeforeEach
  void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    transport =
        new HttpTransport(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private String url(String path) {
    return "http://localhost:" + server.port() + path;
  }

  @Test
  void returnsStatusHeadersBodyAndSizeAndSendsTheIdentifyingUserAgent() {
    server.stubFor(
        get(urlEqualTo("/ok"))
            .willReturn(
                aResponse().withStatus(200).withHeader("X-Test", "a").withBody("{\"x\":\"é\"}")));

    Reply reply = transport.send(url("/ok"));

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.body()).isEqualTo("{\"x\":\"é\"}");
    assertThat(reply.bytes()).isEqualTo(10);
    assertThat(reply.error()).isNull();
    assertThat(reply.latencyMs()).isNotNegative();
    assertThat(reply.headers()).containsKey("x-test");
    assertThat(reply.headers().get("x-test")).containsExactly("a");
    server.verify(
        getRequestedFor(urlEqualTo("/ok"))
            .withHeader(
                "User-Agent",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                    "radar-licitacoes-probe/0.1 (+https://github.com/miguelsanzbr/radar-licitacoes;"
                        + " contato: miguelsanzb.p@gmail.com)")));
  }

  @Test
  void errorStatusesAreReturnedNotThrown() {
    server.stubFor(get(urlEqualTo("/limit")).willReturn(aResponse().withStatus(429)));

    Reply reply = transport.send(url("/limit"));

    assertThat(reply.status()).isEqualTo(429);
    assertThat(reply.error()).isNull();
  }

  @Test
  void timeoutIsRecordedAsMinusOneWithTheErrorClass() {
    server.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withFixedDelay(3000)));

    Reply reply =
        new HttpTransport(Duration.ofSeconds(5), Duration.ofMillis(1500), Duration.ofSeconds(20))
            .send(url("/slow"));

    assertThat(reply.status()).isEqualTo(-1);
    assertThat(reply.error()).contains("HttpTimeoutException");
    assertThat(reply.headers()).isEmpty();
    assertThat(reply.bytes()).isZero();
    assertThat(reply.latencyMs()).isGreaterThanOrEqualTo(1400);
  }

  @Test
  void resetConnectionIsRecordedAsAnError() {
    server.stubFor(
        get(urlEqualTo("/robots.txt"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    Reply reply = transport.send(url("/robots.txt"));

    assertThat(reply.status()).isEqualTo(-1);
    assertThat(reply.error()).isNotBlank();
  }

  @Test
  void interruptionIsRecordedAndTheFlagIsPreserved() {
    server.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
    Thread.currentThread().interrupt();

    Reply reply = transport.send(url("/ok"));

    assertThat(Thread.interrupted()).isTrue();
    assertThat(reply.status()).isEqualTo(-1);
    assertThat(reply.error()).contains("InterruptedException");
  }

  @Test
  void standardTransportUsesTheDocumentedTimeouts() {
    assertThat(HttpTransport.standard()).isNotNull();
  }

  @Test
  void aStalledBodyIsCutByTheWallClockCapAndRecordedAsAnError() throws Exception {
    try (java.net.ServerSocket raw = new java.net.ServerSocket(0)) {
      Thread stall =
          Thread.startVirtualThread(
              () -> {
                try (java.net.Socket client = raw.accept()) {
                  client.getInputStream().read(new byte[4096]);
                  client
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nabcde")
                              .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                  client.getOutputStream().flush();
                  Thread.sleep(5000);
                } catch (java.io.IOException | InterruptedException ignored) {
                  // the test closes the socket when it is done
                }
              });
      HttpTransport capped =
          new HttpTransport(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(800));

      Reply reply = capped.send("http://localhost:" + raw.getLocalPort() + "/stall");
      stall.interrupt();

      assertThat(reply.status()).isEqualTo(-1);
      assertThat(reply.error()).contains("TimeoutException").contains("PT0.8S");
      assertThat(reply.latencyMs()).isBetween(700L, 4000L);
    }
  }
}
