package br.dev.miguelsanzbr.radar.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityConfigTest.Probe.class)
@Import({SecurityConfig.class, SecurityConfigTest.Probe.class})
class SecurityConfigTest {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";
  private static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

  /** One handler behind every kind of path the two chains distinguish. */
  @RestController
  static class Probe {
    @RequestMapping({
      "/api/v1/me",
      "/api/v1/unknown",
      "/ops/probe",
      "/actuator/probe",
      "/buscas/probe",
      "/pagina"
    })
    String ok() {
      return "ok";
    }

    /** A public read that chooses its own caching, as the search controllers must. */
    @RequestMapping("/api/v1/procurements")
    ResponseEntity<String> cached() {
      return ResponseEntity.ok().cacheControl(CacheControl.noCache()).eTag("\"v1\"").body("ok");
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder decoder;

  private void token(String value, Map<String, Object> claims) {
    when(decoder.decode(value))
        .thenReturn(
            Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject("kc-1")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
  }

  private static ResultMatcher problem(int status, String code) {
    return result -> {
      status().is(status).match(result);
      content().contentTypeCompatibleWith("application/problem+json").match(result);
      jsonPath("$.type").value(DOCS + code + ".md").match(result);
      jsonPath("$.status").value(status).match(result);
      jsonPath("$.title").isString().match(result);
      jsonPath("$.detail").isString().match(result);
    };
  }

  @Test
  void realmRolesOfTheBearerTokenBecomeAuthorities() throws Exception {
    token("supplier", Map.of("realm_access", Map.of("roles", List.of("supplier", "x"))));
    token("operator", Map.of("realm_access", Map.of("roles", List.of("operator"))));

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer supplier"))
        .andExpect(status().isOk());
    mvc.perform(get("/ops/probe").header("Authorization", "Bearer supplier"))
        .andExpect(problem(403, "forbidden"));
    mvc.perform(get("/ops/probe").header("Authorization", "Bearer operator"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer operator"))
        .andExpect(problem(403, "forbidden"));
  }

  @Test
  void tokenWithoutRealmRolesHasNoRole() throws Exception {
    token("none", Map.of());
    token("no-list", Map.of("realm_access", Map.of("roles", "supplier")));

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer none"))
        .andExpect(problem(403, "forbidden"));
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer no-list"))
        .andExpect(problem(403, "forbidden"));
  }

  @Test
  void missingOrInvalidTokenIs401ProblemWithBearerChallenge() throws Exception {
    when(decoder.decode("forged")).thenThrow(new BadJwtException("bad signature"));

    mvc.perform(get("/api/v1/me"))
        .andExpect(problem(401, "unauthorized"))
        .andExpect(header().string("WWW-Authenticate", "Bearer"));
    mvc.perform(get("/api/v1/procurements").header("Authorization", "Bearer forged"))
        .andExpect(problem(401, "unauthorized"))
        .andExpect(header().string("WWW-Authenticate", "Bearer"));
  }

  @Test
  void visitorReadsTheApiButCannotWriteToIt() throws Exception {
    mvc.perform(get("/api/v1/procurements")).andExpect(status().isOk());
    mvc.perform(post("/api/v1/procurements")).andExpect(problem(401, "unauthorized"));
    mvc.perform(post("/api/v1/procurements").with(Auth.supplier("kc-1")))
        .andExpect(problem(403, "forbidden"));
  }

  @Test
  void anApiKeyIsNotACredentialOnAnyProtectedRoute() throws Exception {
    for (var path : List.of("/api/v1/me", "/ops/probe", "/actuator/probe", "/api/v1/unknown")) {
      mvc.perform(get(path).header("X-Api-Key", "rk_abcd1234_secret"))
          .andExpect(problem(401, "unauthorized"));
    }
    mvc.perform(post("/api/v1/procurements").header("X-Api-Key", "rk_abcd1234_secret"))
        .andExpect(problem(401, "unauthorized"));
  }

  @Test
  void apiPathsOutsideTheContractAreDeniedEvenForReading() throws Exception {
    mvc.perform(get("/api/v1/unknown")).andExpect(problem(401, "unauthorized"));
    mvc.perform(get("/api/v1/unknown").with(Auth.supplier("kc-1")))
        .andExpect(problem(403, "forbidden"));
    mvc.perform(get("/api/v1/unknown").with(Auth.operator())).andExpect(problem(403, "forbidden"));
  }

  @Test
  void apiIsStatelessAndNeedsNoCsrfToken() throws Exception {
    token("supplier", Map.of("realm_access", Map.of("roles", List.of("supplier"))));

    var request =
        mvc.perform(post("/api/v1/me").header("Authorization", "Bearer supplier"))
            .andExpect(status().isOk())
            .andExpect(cookie().doesNotExist("XSRF-TOKEN"))
            .andReturn()
            .getRequest();

    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void opsAndActuatorNeedTheOperatorRole() throws Exception {
    mvc.perform(get("/actuator/probe")).andExpect(problem(401, "unauthorized"));
    mvc.perform(get("/actuator/probe").with(Auth.supplier("kc-1")))
        .andExpect(problem(403, "forbidden"));
    mvc.perform(get("/actuator/probe").with(Auth.operator())).andExpect(status().isOk());
    mvc.perform(post("/ops/probe")).andExpect(problem(401, "unauthorized"));
  }

  /**
   * What the pages' scripts do: read the XSRF-TOKEN cookie and echo it in X-XSRF-TOKEN. This class
   * avoids csrf(), which would swap the cookie repository of the shared context for a session one.
   */
  @Test
  void pagesPostOnlyWithTheCookieTokenEchoedInAHeader() throws Exception {
    var supplier = Auth.webSupplier("kc-1", "fulano@example.com");
    var cookie =
        mvc.perform(get("/pagina"))
            .andExpect(status().isOk())
            .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
            .andReturn()
            .getResponse()
            .getCookie("XSRF-TOKEN");

    mvc.perform(
            post("/buscas/probe")
                .with(supplier)
                .cookie(cookie)
                .header("X-XSRF-TOKEN", cookie.getValue()))
        .andExpect(status().isOk());
    mvc.perform(
            post("/buscas/probe").with(supplier).cookie(cookie).header("X-XSRF-TOKEN", "forged"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/buscas/probe").with(supplier).cookie(cookie))
        .andExpect(status().isForbidden());
    mvc.perform(post("/buscas/probe").with(supplier)).andExpect(status().isForbidden());
  }

  @Test
  void visitorOnASupplierPageIsSentToTheLogin() throws Exception {
    mvc.perform(get("/buscas/probe"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/oauth2/authorization/keycloak"));
    mvc.perform(get("/buscas/probe").with(Auth.webOperator())).andExpect(status().isForbidden());
  }

  @Test
  void everyResponseCarriesTheSecurityHeaders() throws Exception {
    // a page, a public read, a 401 problem, a 404 and a static file
    for (var path :
        List.of(
            "/pagina", "/api/v1/procurements", "/api/v1/me", "/nao-existe", "/css/alertas.css")) {
      mvc.perform(get(path))
          .andExpect(header().string("Content-Security-Policy", CSP))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
          .andExpect(header().string("X-Frame-Options", "DENY"))
          .andExpect(header().doesNotExist("Strict-Transport-Security"));
      mvc.perform(get(path).secure(true))
          .andExpect(
              header()
                  .string(
                      "Strict-Transport-Security",
                      org.hamcrest.Matchers.startsWith("max-age=31536000")));
    }
  }

  @Test
  void cachingIsLeftToTheHandlerOrToTheStaticResourceSettings() throws Exception {
    mvc.perform(get("/api/v1/procurements"))
        .andExpect(header().string("Cache-Control", "no-cache"))
        .andExpect(header().string("ETag", "\"v1\""));
    mvc.perform(get("/css/alertas.css"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "max-age=3600"));
    mvc.perform(get("/api/v1/me"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
  }

  @Autowired JwtDecoderFactory<ClientRegistration> idTokenDecoders;

  @Test
  void idTokenFromAnotherIssuerIsRejected() throws Exception {
    assertThat(idTokenDecoders).isInstanceOf(OidcIdTokenDecoderFactory.class);
    var factory = SecurityConfig.idTokenDecoders("http://localhost:8180/realms/radar");
    // HMAC with the client secret, so the test needs no JWKS endpoint.
    factory.setJwsAlgorithmResolver(registration -> MacAlgorithm.HS256);
    var secret = "a-client-secret-of-at-least-32-bytes";
    var decoder =
        factory.createDecoder(
            ClientRegistration.withRegistrationId("keycloak")
                .clientId("radar-web")
                .clientSecret(secret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                .authorizationUri("http://localhost:8180/auth")
                .tokenUri("http://localhost:8180/token")
                .build());

    assertThat(decoder.decode(idToken("http://localhost:8180/realms/radar", secret)).getSubject())
        .isEqualTo("kc-1");
    assertThatThrownBy(() -> decoder.decode(idToken("http://evil.example/realms/radar", secret)))
        .isInstanceOf(JwtValidationException.class)
        .hasMessageContaining("iss");
  }

  private static String idToken(String issuer, String secret) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("kc-1")
            .audience("radar-web")
            .issueTime(new Date())
            .expirationTime(new Date(System.currentTimeMillis() + 60_000))
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  @Test
  void browserLoginAddsTheRealmRolesOfTheIdToken() {
    var idToken =
        new OidcIdToken(
            "t",
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(60),
            Map.of("sub", "kc-1", "realm_access", Map.of("roles", List.of("supplier"))));
    var scope = new SimpleGrantedAuthority("SCOPE_openid");

    var mapped = SecurityConfig.withRealmRoles(List.of(new OidcUserAuthority(idToken), scope));

    assertThat(mapped.stream().map(GrantedAuthority::getAuthority))
        .containsExactlyInAnyOrder("OIDC_USER", "SCOPE_openid", "ROLE_supplier");
  }
}
