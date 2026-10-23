package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpBlockedException;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

/** What reaches a stubbed PNCP when it blocks the client, and what still retries as before. */
class PncpBlockedIT {

  private static final String PUBLICATION = "/api/consulta/v1/contratacoes/publicacao";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T07:00:00Z"));
  private PncpHttpGateway gateway;

  @AfterEach
  void close() {
    gateway.close();
  }

  private PncpHttpGateway gateway(Duration requestTimeout, int attempts) {
    var props =
        new PncpProperties(
            wm.baseUrl(),
            50,
            1000,
            requestTimeout,
            Duration.ofSeconds(2),
            new PncpProperties.Retry(attempts, Duration.ofMillis(10), 2.0, 0.5),
            new PncpProperties.CircuitBreaker(
                100, 50f, Duration.ofSeconds(60), Duration.ofSeconds(10)),
            4,
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofMinutes(30));
    gateway =
        new PncpHttpGateway(props, JsonMapper.builder().build(), new SimpleMeterRegistry(), clock);
    return gateway;
  }

  @Test
  void tooManyRequestsReachesThePncpOnceAndThenNeverDuringThePause() {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withStatus(429).withBody("<html>blocked</html>")));
    var g = gateway(Duration.ofSeconds(5), 4);

    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpBlockedException.class);
    assertThat(wm.getAllServeEvents()).hasSize(1);

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
          .isInstanceOf(PncpBlockedException.class);
    }
    assertThat(wm.getAllServeEvents()).hasSize(1);
  }

  @Test
  void forbiddenAlsoReachesThePncpOnce() {
    wm.stubFor(get(urlPathEqualTo(PUBLICATION)).willReturn(aResponse().withStatus(403)));
    var g = gateway(Duration.ofSeconds(5), 4);

    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpBlockedException.class);
    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpBlockedException.class);

    assertThat(wm.getAllServeEvents()).hasSize(1);
  }

  @Test
  void serverErrorsStillRetryUntilSuccess() throws IOException {
    var states = new String[] {Scenario.STARTED, "s1", "s2"};
    var responses =
        new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder[] {
          aResponse().withStatus(500),
          aResponse().withStatus(503),
          okJson(PncpMapperTest.fixture("publicacao-page2.json"))
        };
    for (int i = 0; i < states.length; i++) {
      wm.stubFor(
          get(urlPathEqualTo(PUBLICATION))
              .inScenario("seq")
              .whenScenarioStateIs(states[i])
              .willSetStateTo("s" + (i + 1))
              .willReturn(responses[i]));
    }

    var page = gateway(Duration.ofSeconds(5), 4).publications(DAY, DAY, 6, 1);

    assertThat(page.items()).hasSize(1);
    assertThat(wm.getAllServeEvents()).hasSize(3);
  }

  @Test
  void timeoutsStillRetryAndAreNotABlock() {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withFixedDelay(3_000).withStatus(200)));
    var g = gateway(Duration.ofMillis(300), 3);

    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpUnavailableException.class)
        .isNotInstanceOf(PncpBlockedException.class);

    assertThat(wm.getAllServeEvents()).hasSize(3);
  }
}
