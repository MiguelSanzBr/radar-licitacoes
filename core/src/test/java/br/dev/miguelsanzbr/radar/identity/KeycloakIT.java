package br.dev.miguelsanzbr.radar.identity;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.SharedKeycloak;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real tokens from the realm in infra/keycloak, validated by the application's resource server. */
class KeycloakIT extends PostgresIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void issuer(DynamicPropertyRegistry registry) {
    registry.add("radar.oidc.issuer", SharedKeycloak::issuer);
    // why: one more cached context on the shared Postgres; keep its pool small
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
  }

  @Autowired MockMvc mvc;

  /** Direct grant (enabled only in the test realm) with the dev users' default passwords. */
  private static JsonNode login(String user) throws Exception {
    return login("client_id=radar-web&client_secret=radar-web-dev-secret", user);
  }

  private static JsonNode login(String client, String user) throws Exception {
    var form =
        "grant_type=password&scope=openid&"
            + client
            + "&username="
            + URLEncoder.encode(user, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(user, StandardCharsets.UTF_8);
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(SharedKeycloak.issuer() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static JsonNode claims(String jwt) {
    return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
  }

  private static MockHttpServletRequestBuilder createSearch(String accessToken) {
    return post("/api/v1/saved-searches")
        .header("Authorization", "Bearer " + accessToken)
        .header("Idempotency-Key", "key-keycloak-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"name":"Notebooks","query":"notebook","frequency":"DAILY"}
            """);
  }

  @Test
  void supplierTokenFromKeycloakSavesASearch() throws Exception {
    var accessToken = login("fornecedor").get("access_token").asString();
    jdbc.sql(
            "INSERT INTO alerts.subscriber (id, email, consent_at, verified_at, created_at,"
                + " external_subject) VALUES (:id, 'fornecedor@example.com', now(), now(), now(), :s)")
        .param("id", UUID.randomUUID())
        .param("s", claims(accessToken).get("sub").asString())
        .update();

    mvc.perform(createSearch(accessToken))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Notebooks"))
        .andExpect(conforms());
  }

  @Test
  void operatorTokenFromKeycloakIsForbiddenOnASupplierRoute() throws Exception {
    var accessToken = login("operador").get("access_token").asString();

    mvc.perform(createSearch(accessToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("forbidden.md")))
        .andExpect(conforms());
  }

  @Test
  void tamperedTokenIs401() throws Exception {
    var accessToken = login("fornecedor").get("access_token").asString();
    var tampered = accessToken.substring(0, accessToken.length() - 4) + "AAAA";

    mvc.perform(createSearch(tampered)).andExpect(status().isUnauthorized()).andExpect(conforms());
  }

  @Test
  void tokenOfAnotherClientOfTheSameRealmLacksTheAudienceAndIs401() throws Exception {
    var accessToken = login("client_id=admin-cli", "fornecedor").get("access_token").asString();
    assertThat(claims(accessToken).toString()).doesNotContain("radar-api");

    mvc.perform(createSearch(accessToken))
        .andExpect(status().isUnauthorized())
        .andExpect(conforms());
  }

  @Test
  void idTokenCarriesWhatTheBrowserLoginNeeds() throws Exception {
    var tokens = login("fornecedor");
    var idToken = claims(tokens.get("id_token").asString());
    var accessToken = claims(tokens.get("access_token").asString());

    assertThat(idToken.get("email").asString()).isEqualTo("fornecedor@example.com");
    assertThat(idToken.at("/realm_access/roles").values())
        .extracting(JsonNode::asString)
        .contains("supplier");
    assertThat(accessToken.get("aud").toString()).contains("radar-api");
  }

  @Test
  void testRealmIsTheDevRealmPlusDirectGrants() throws Exception {
    var dev = JSON.readTree(Path.of("..", "infra", "keycloak", "realm-radar.json").toFile());
    var test =
        JSON.readTree(KeycloakIT.class.getResourceAsStream("/keycloak/realm-radar-test.json"));

    var enabled = 0;
    for (var client : test.get("clients")) {
      if (client.get("directAccessGrantsEnabled").asBoolean()) {
        ((ObjectNode) client).put("directAccessGrantsEnabled", false);
        enabled++;
      }
    }
    assertThat(enabled).isEqualTo(2); // radar-web and admin-cli
    assertThat(test).isEqualTo(dev);
  }
}
