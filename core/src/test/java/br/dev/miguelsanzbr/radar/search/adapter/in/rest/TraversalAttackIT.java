package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Path-like input where an identifier or a cursor is expected, and requests for files the
 * application keeps on its classpath (OWASP A01 and A05). Nothing here may read a file: a bad
 * cursor is a 400 problem, a bad id or static path is a 400 (rejected by the firewall) or a 404.
 */
class TraversalAttackIT extends PostgresIT {

  @Autowired MockMvc mvc;

  private static String cursor(String plain) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  private int status(String uri) throws Exception {
    return mvc.perform(get(URI.create(uri))).andReturn().getResponse().getStatus();
  }

  private String body(String uri) throws Exception {
    return mvc.perform(get(URI.create(uri))).andReturn().getResponse().getContentAsString();
  }

  static Stream<String> brokenCursors() {
    return Stream.of(
            "v1|p|0|a\u0000b",
            "v1|p|0|a\nb",
            "v1|r|0.5|a\u0000b",
            "v1|p|99999999999999999999|id",
            "v1|p|9223372036854775807|id",
            "v1|p|-9223372036854775808|id",
            "v1|r|NaN|id",
            "v1|r|Infinity|id",
            "v1|r|1e999|id",
            "v1|x|0|id",
            "v2|p|0|id",
            "v1|p|0|",
            "v1|p|0",
            "..",
            "../../etc/passwd",
            "%2e%2e/%2e%2e/etc/passwd")
        .map(TraversalAttackIT::cursor);
  }

  @ParameterizedTest
  @MethodSource("brokenCursors")
  void aCursorThatIsNotOneIsA400Problem(String cursor) throws Exception {
    var response =
        mvc.perform(get("/api/v1/procurements").param("cursor", cursor)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
  }

  @ParameterizedTest
  @MethodSource("brokenCursors")
  void theFragmentAndThePageAnswerABrokenCursorWithA400Page(String cursor) throws Exception {
    for (var path : List.of("/", "/fragments/results")) {
      var response = mvc.perform(get(path).param("cursor", cursor)).andReturn().getResponse();

      assertThat(response.getStatus()).as(path).isEqualTo(400);
      assertThat(response.getContentAsString()).doesNotContain("Exception", "at br.dev");
    }
  }

  @Test
  void textThatIsNotBase64IsA400Problem() throws Exception {
    for (var text :
        List.of("../../etc/passwd", "%2e%2e%2f", "\u0000", "a b", "é", "=", "x".repeat(600))) {
      var response =
          mvc.perform(get("/api/v1/procurements").param("cursor", text)).andReturn().getResponse();

      assertThat(response.getStatus()).as(text).isEqualTo(400);
    }
  }

  /** The id of a cursor is a sort key bound as a value: path-like text is not a file name. */
  @Test
  void aPathLikeCursorIdIsOnlyAKeyAndReadsNoFile() throws Exception {
    var response =
        mvc.perform(
                get("/api/v1/procurements")
                    .param("cursor", cursor("v1|p|0|../../../../etc/passwd")))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).doesNotContain("root:");
  }

  static Stream<String> hostileIds() {
    return Stream.of(
        "..",
        "%2e%2e",
        "%2e%2e%2f%2e%2e%2fetc%2fpasswd",
        "..%2f..%2f..%2fetc%2fpasswd",
        "..%5c..%5cwindows%5cwin.ini",
        "....//....//etc/passwd",
        "a%2fb",
        "a%252fb",
        "a%00b",
        "a;b/../c",
        "%2e",
        "..;/etc/passwd",
        "x".repeat(5_000));
  }

  @ParameterizedTest
  @MethodSource("hostileIds")
  void anIdThatIsAPathIsRefusedOrNotFound(String id) throws Exception {
    for (var path :
        List.of("/api/v1/procurements/%s", "/api/v1/procurements/%s/history", "/licitacoes/%s")) {
      var status = status(path.formatted(id));

      assertThat(status).as(path.formatted(id)).isIn(400, 404);
    }
  }

  /** The resources a supplier owns are named by an 8-character prefix or a UUID, nothing else. */
  @ParameterizedTest
  @MethodSource("hostileIds")
  void aSupplierRoutePathThatIsNotAnIdentifierIsRefused(String id) throws Exception {
    for (var base : List.of("/api/v1/api-keys/", "/api/v1/saved-searches/")) {
      for (var method : List.of(HttpMethod.GET, HttpMethod.DELETE)) {
        var status =
            mvc.perform(request(method, URI.create(base + id)).with(Auth.supplier("s-1")))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).as(method + " " + base + id).isIn(400, 403, 404, 405);
      }
    }
  }

  static Stream<String> staticPaths() {
    return Stream.of(
        "/application.properties",
        "/config/application.properties",
        "/db/migration/V12__identity_api_key.sql",
        "/templates/ops/painel.html",
        "/META-INF/MANIFEST.MF",
        "/br/dev/miguelsanzbr/radar/RadarApplication.class",
        "/css/../application.properties",
        "/js/%2e%2e/application.properties",
        "/css/%2e%2e/%2e%2e/application.properties",
        "/%2e%2e/application.properties",
        "/css/..%2fapplication.properties",
        "/css/%252e%252e/application.properties",
        "/css/..;/application.properties",
        "/css/..%5capplication.properties",
        "/css//..//application.properties",
        "/static/../application.properties",
        "/css/alertas.css%00.png",
        "/css/app.css/../../application.properties",
        "/js/htmx-2.0.11.min.js/../../application.properties",
        "/js/..%2fapplication.properties",
        "/css/app-00000000000000000000000000000000.css/../../application.properties",
        "/js/%2e%2e/%2e%2e/etc/passwd",
        "/../../../../etc/passwd");
  }

  @ParameterizedTest
  @MethodSource("staticPaths")
  void classpathFilesAreNotServed(String path) throws Exception {
    var status = status(path);

    assertThat(status).as(path).isIn(400, 404);
    assertThat(body(path)).doesNotContain("spring.", "CREATE TABLE", "root:");
  }

  @Test
  void theStaticFilesThatAreMeantToBePublicStillAre() throws Exception {
    assertThat(status("/css/alertas.css")).isEqualTo(200);
    assertThat(status("/css/app.css")).isEqualTo(200);
    assertThat(status("/js/htmx-2.0.11.min.js")).isEqualTo(200);
  }
}
