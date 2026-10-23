package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpProtocolException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway only ever talks to the configured host (OWASP A10). Its query takes dates and
 * numbers, so no argument can carry an address; the one way left to reach another host is a
 * redirect from the configured one, and it is not followed.
 */
class PncpGatewayHostTest {

  private static final String PUBLICATION = "/api/consulta/v1/contratacoes/publicacao";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

  @RegisterExtension
  static WireMockExtension configured =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @RegisterExtension
  static WireMockExtension elsewhere =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private PncpHttpGateway gateway;

  @AfterEach
  void close() {
    gateway.close();
  }

  private PncpHttpGateway gateway() {
    var props =
        new PncpProperties(
            configured.baseUrl(),
            50,
            1000,
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            new PncpProperties.Retry(1, Duration.ofMillis(10), 2.0, 0.5),
            new PncpProperties.CircuitBreaker(
                100, 50f, Duration.ofSeconds(60), Duration.ofSeconds(10)),
            4,
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofMinutes(30));
    gateway =
        new PncpHttpGateway(
            props,
            JsonMapper.builder().build(),
            new SimpleMeterRegistry(),
            new MutableClock(Instant.parse("2026-10-21T07:00:00Z")));
    return gateway;
  }

  @Test
  void aRedirectToAnotherHostIsNotFollowed() {
    elsewhere.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    configured.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", elsewhere.baseUrl() + "/internal/secret")));

    assertThatThrownBy(() -> gateway().publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpProtocolException.class);

    elsewhere.verify(0, anyRequestedFor(anyUrl()));
    configured.verify(1, anyRequestedFor(anyUrl()));
  }

  @Test
  void everyRequestGoesToTheConfiguredHostAtAFixedPath() {
    configured.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(204)));

    gateway().publications(DAY, DAY.plusDays(1), 6, 3);
    gateway.updates(DAY, DAY.plusDays(1), 6, 3);

    assertThat(configured.getAllServeEvents())
        .extracting(event -> event.getRequest().getUrl().split("\\?")[0])
        .containsExactlyInAnyOrder(PUBLICATION, "/api/consulta/v1/contratacoes/atualizacao");
    elsewhere.verify(0, anyRequestedFor(anyUrl()));
  }
}
