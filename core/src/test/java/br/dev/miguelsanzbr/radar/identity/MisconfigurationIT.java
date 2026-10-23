package br.dev.miguelsanzbr.radar.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.application.ReplayUseCase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the running application shows to anyone who goes looking (OWASP A05): management endpoints,
 * the container's error body and the headers of every kind of response.
 */
class MisconfigurationIT extends PostgresIT {

  private static final List<String> NOT_EXPOSED =
      List.of(
          "env",
          "beans",
          "configprops",
          "heapdump",
          "threaddump",
          "loggers",
          "mappings",
          "conditions",
          "caches",
          "httpexchanges",
          "scheduledtasks",
          "flyway",
          "sbom",
          "auditevents");

  // same mock as AuthorizationMatrixIT, so both tests share one application context
  @MockitoBean ReplayUseCase replay;

  @Autowired MockMvc mvc;

  @Test
  void theOperatorFindsNoManagementEndpointBeyondTheOnesDocumented() throws Exception {
    for (var name : NOT_EXPOSED) {
      var status =
          mvc.perform(get("/actuator/" + name).with(Auth.operator()))
              .andReturn()
              .getResponse()
              .getStatus();

      assertThat(status).as("/actuator/" + name).isEqualTo(404);
    }
    for (var name : List.of("shutdown", "restart", "refresh")) {
      var status =
          mvc.perform(post("/actuator/" + name).with(Auth.operator()))
              .andReturn()
              .getResponse()
              .getStatus();

      assertThat(status).as("POST /actuator/" + name).isIn(404, 405);
    }
  }

  @Test
  void theHealthAnswerShowsTheStatusAndNoComponentDetails() throws Exception {
    for (var caller : List.of(Auth.operator(), Auth.supplier("s"))) {
      var body = mvc.perform(get("/actuator/health").with(caller)).andReturn().getResponse();

      assertThat(body.getContentAsString()).contains("\"status\"").doesNotContain("components");
    }
    var anonymous = mvc.perform(get("/actuator/health")).andReturn().getResponse();
    assertThat(anonymous.getContentAsString()).doesNotContain("components", "details", "jdbc");
  }

  @Test
  void theContainersErrorBodyCarriesNoMessageTraceOrExceptionName() throws Exception {
    var response =
        mvc.perform(
                get("/error")
                    .accept("application/json")
                    .requestAttr("jakarta.servlet.error.status_code", 500)
                    .requestAttr("jakarta.servlet.error.message", "segredo interno")
                    .requestAttr(
                        "jakarta.servlet.error.exception",
                        new IllegalStateException("segredo interno")))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString())
        .doesNotContain("segredo", "IllegalState", "trace", "exception", "at br.dev");
  }

  @Test
  void everyKindOfResponseCarriesTheSecurityHeaders() throws Exception {
    // the search page and its fragment, a public read, a 401, a 404, a static file
    for (var path :
        List.of(
            "/",
            "/fragments/results",
            "/api/v1/procurements",
            "/api/v1/me",
            "/nao-existe",
            "/css/app.css")) {
      var response = mvc.perform(get(path)).andReturn().getResponse();

      var policy = response.getHeader("Content-Security-Policy");
      assertThat(policy).as(path).contains("default-src 'self'", "frame-ancestors 'none'");
      assertThat(policy).as(path).doesNotContain("unsafe-inline", "unsafe-eval");
      assertThat(response.getHeader("X-Content-Type-Options")).as(path).isEqualTo("nosniff");
      assertThat(response.getHeader("Referrer-Policy"))
          .as(path)
          .isEqualTo("strict-origin-when-cross-origin");
      assertThat(response.getHeader("X-Frame-Options")).as(path).isEqualTo("DENY");
    }
  }

  /** No CORS configuration: a page of another origin cannot read the API from a browser. */
  @Test
  void noResponseGrantsACrossOriginRead() throws Exception {
    var origin = "https://evil.example";

    var read = mvc.perform(get("/api/v1/organs").param("q", "ab").header("Origin", origin));
    var preflight =
        mvc.perform(
            options("/api/v1/procurements")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET"));

    for (var response : List.of(read, preflight)) {
      assertThat(response.andReturn().getResponse().getHeader("Access-Control-Allow-Origin"))
          .isNull();
    }
  }
}
