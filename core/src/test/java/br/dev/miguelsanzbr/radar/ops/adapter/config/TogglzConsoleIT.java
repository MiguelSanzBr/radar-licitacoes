package br.dev.miguelsanzbr.radar.ops.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.outbox.OutboxTestConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The flag console over real HTTP, because it is a servlet that MockMvc never reaches. A test only
 * login puts a role in the browser session, as the OIDC login does in production.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"radar.outbox.relay-enabled=false", "radar.outbox.publisher=inmemory"})
@ActiveProfiles("test")
@Import({PostgresTestConfig.class, OutboxTestConfig.class, TogglzConsoleIT.Login.class})
class TogglzConsoleIT {

  @RestController
  static class LoginController {

    @GetMapping("/test-login/{role}")
    String login(
        @PathVariable String role, HttpServletRequest request, HttpServletResponse response) {
      var authentication =
          UsernamePasswordAuthenticationToken.authenticated(
              "user-" + role, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authentication);
      new HttpSessionSecurityContextRepository().saveContext(context, request, response);
      return "ok";
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Login {
    @Bean
    LoginController loginController() {
      return new LoginController();
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  @AfterEach
  void backToTheDefaults() {
    jdbc.sql("TRUNCATE ops.togglz_feature").update();
  }

  /** A browser with its own cookies, logged in as {@code role} when one is given. */
  private HttpClient browser(String role) throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    if (role != null) {
      client.send(
          HttpRequest.newBuilder(URI.create(base() + "/test-login/" + role)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
    return client;
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(HttpClient client, String path, String form) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aVisitorIsSentToTheLogin() throws Exception {
    var response = get(browser(null), "/ops/togglz/index");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElseThrow())
        .endsWith("/oauth2/authorization/keycloak");
  }

  @Test
  void aSupplierIsForbidden() throws Exception {
    assertThat(get(browser("supplier"), "/ops/togglz/index").statusCode()).isEqualTo(403);
  }

  @Test
  void anOperatorSeesTheFeatures() throws Exception {
    var response = get(browser("operator"), "/ops/togglz/index");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("INCREMENTAL_SYNC", "ALERT_DIGEST");
  }

  private static String field(String page, String name) {
    var token = Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(page);
    assertThat(token.find()).as("hidden field " + name).isTrue();
    return token.group(1);
  }

  private static final String EDIT = "&f=INCREMENTAL_SYNC&enabled=enabled&strategy=";

  @Test
  void anOperatorSwitchesAFeatureWithBothTokens() throws Exception {
    var operator = browser("operator");
    var index = get(operator, "/ops/togglz/index").body();

    var saved =
        post(
            operator,
            "/ops/togglz/edit",
            "_csrf="
                + field(index, "_csrf")
                + "&togglz_csrf="
                + field(index, "togglz_csrf")
                + EDIT);

    assertThat(saved.statusCode()).isEqualTo(302);
    assertThat(
            jdbc.sql(
                    "SELECT feature_enabled FROM ops.togglz_feature WHERE feature_name ="
                        + " 'INCREMENTAL_SYNC'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void aPostWithoutTheSessionCsrfTokenIsRejectedBeforeTheConsole() throws Exception {
    var operator = browser("operator");
    var index = get(operator, "/ops/togglz/index").body();

    var response =
        post(operator, "/ops/togglz/edit", "togglz_csrf=" + field(index, "togglz_csrf") + EDIT);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(jdbc.sql("SELECT count(*) FROM ops.togglz_feature").query(Integer.class).single())
        .isZero();
  }

  @Test
  void aPostWithoutTheTokenOfTheConsoleChangesNothing() throws Exception {
    var operator = browser("operator");
    var index = get(operator, "/ops/togglz/index").body();

    var response = post(operator, "/ops/togglz/edit", "_csrf=" + field(index, "_csrf") + EDIT);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(jdbc.sql("SELECT count(*) FROM ops.togglz_feature").query(Integer.class).single())
        .isZero();
  }
}
