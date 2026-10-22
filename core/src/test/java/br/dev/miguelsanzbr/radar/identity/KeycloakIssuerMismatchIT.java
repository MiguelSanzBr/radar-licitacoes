package br.dev.miguelsanzbr.radar.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.SharedKeycloak;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Keys from the right realm are not enough: the token must come from the configured issuer. */
class KeycloakIssuerMismatchIT extends PostgresIT {

  @DynamicPropertySource
  static void keysFromKeycloakButAnotherIssuer(DynamicPropertyRegistry registry) {
    registry.add("radar.oidc.issuer", SharedKeycloak::issuer);
    // why: one more cached context on the shared Postgres; keep its pool small
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "http://idp.example/realms/radar");
  }

  @Autowired MockMvc mvc;

  @Test
  void validTokenFromAnUnexpectedIssuerIs401() throws Exception {
    var form =
        "grant_type=password&client_id=radar-web&client_secret=radar-web-dev-secret"
            + "&username=fornecedor&password=fornecedor";
    var body =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(SharedKeycloak.issuer() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    var accessToken = JsonMapper.builder().build().readTree(body).get("access_token").asString();

    mvc.perform(delete("/api/v1/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isUnauthorized());
  }
}
