package br.dev.miguelsanzbr.radar.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * Runs every route of the contract and every page as each kind of caller and writes what the
 * application actually answered to docs/security/authz-matrix.md, which must be committed as is.
 */
class AuthorizationMatrixIT extends PostgresIT {

  private static final Path MATRIX = Path.of("..", "docs", "security", "authz-matrix.md");
  private static final String SUBJECT = "matrix-supplier";
  private static final String SEARCH =
      "/api/v1/saved-searches/0198a3a0-0000-7000-8000-000000000002";

  enum Caller {
    VISITOR("visitante"),
    SUPPLIER("supplier"),
    OPERATOR("operator");

    final String label;

    Caller(String label) {
      this.label = label;
    }
  }

  /** {@code page} routes run with a browser session and a CSRF token, the others with a JWT. */
  record Route(String method, String label, String path, Caller needs, boolean page) {

    Route(String method, String path, Caller needs, boolean page) {
      this(method, path, path, needs, page);
    }

    @Override
    public String toString() {
      return method + " " + label;
    }
  }

  private static final List<Route> ROUTES =
      List.of(
          new Route("GET", "/api/v1/procurements", Caller.VISITOR, false),
          new Route(
              "GET",
              "/api/v1/procurements/{id}",
              "/api/v1/procurements/12345678000190-1-000123-2026",
              Caller.VISITOR,
              false),
          new Route(
              "GET",
              "/api/v1/procurements/{id}/history",
              "/api/v1/procurements/12345678000190-1-000123-2026/history",
              Caller.VISITOR,
              false),
          new Route("GET", "/api/v1/stats", Caller.VISITOR, false),
          new Route("GET", "/api/v1/organs", Caller.VISITOR, false),
          new Route("POST", "/api/v1/saved-searches", Caller.SUPPLIER, false),
          new Route("GET", "/api/v1/saved-searches/{id}", SEARCH, Caller.SUPPLIER, false),
          new Route("DELETE", "/api/v1/saved-searches/{id}", SEARCH, Caller.SUPPLIER, false),
          new Route("DELETE", "/api/v1/me", Caller.SUPPLIER, false),
          new Route("POST", "/api/v1/api-keys", Caller.SUPPLIER, false),
          new Route("GET", "/ops/health-panel", Caller.OPERATOR, false),
          new Route("POST", "/ops/replay", Caller.OPERATOR, false),
          new Route(
              "POST",
              "/ops/dlq/{topic}/reprocess",
              "/ops/dlq/procurement-events/reprocess",
              Caller.OPERATOR,
              false),
          new Route("POST", "/ops/backfill", Caller.OPERATOR, false),
          new Route("GET", "/actuator/health", Caller.VISITOR, false),
          new Route("GET", "/actuator/info", Caller.VISITOR, false),
          new Route("GET", "/actuator/metrics", Caller.OPERATOR, false),
          new Route("GET", "/actuator/prometheus", Caller.OPERATOR, false),
          new Route("GET", "/privacidade", Caller.VISITOR, true),
          new Route("GET", "/assinatura/confirmar", Caller.VISITOR, true),
          new Route("GET", "/assinatura", Caller.SUPPLIER, true),
          new Route("POST", "/assinatura", Caller.SUPPLIER, true),
          new Route("GET", "/buscas/nova", Caller.SUPPLIER, true),
          new Route("POST", "/buscas", Caller.SUPPLIER, true));

  private static final Map<Route, Map<Caller, String>> RESULTS = new LinkedHashMap<>();

  @Autowired MockMvc mvc;

  static Stream<Arguments> cases() {
    return ROUTES.stream()
        .flatMap(route -> Stream.of(Caller.values()).map(caller -> Arguments.of(route, caller)));
  }

  @BeforeEach
  void supplierHasConsented() {
    jdbc.sql(
            "INSERT INTO alerts.subscriber (id, email, consent_at, verified_at, created_at,"
                + " external_subject) VALUES (:id, 'matrix@example.com', now(), now(), now(), :s)")
        .param("id", UUID.randomUUID())
        .param("s", SUBJECT)
        .update();
  }

  @ParameterizedTest(name = "{0} como {1}")
  @MethodSource("cases")
  void routeAnswersAsItsRoleRequires(Route route, Caller caller) throws Exception {
    var request =
        request(HttpMethod.valueOf(route.method()), route.path())
            .header("Idempotency-Key", "matrix-key-1")
            .param("consent", "on")
            .param("token", "matrix")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"n\",\"query\":\"q\",\"frequency\":\"DAILY\"}");
    if (route.page()) {
      request.with(csrf());
    }
    switch (caller) {
      case SUPPLIER ->
          request.with(
              route.page()
                  ? Auth.webSupplier(SUBJECT, "matrix@example.com")
                  : Auth.supplier(SUBJECT));
      case OPERATOR -> request.with(route.page() ? Auth.webOperator() : Auth.operator());
      case VISITOR -> {}
    }

    var outcome = outcome(mvc.perform(request).andReturn().getResponse());

    synchronized (RESULTS) {
      RESULTS.computeIfAbsent(route, r -> new EnumMap<>(Caller.class)).put(caller, outcome);
    }
    assertThat(outcome).isEqualTo(expected(route, caller));
  }

  private static String expected(Route route, Caller caller) {
    if (route.needs() == Caller.VISITOR || route.needs() == caller) {
      return "permitido";
    }
    if (caller != Caller.VISITOR) {
      return "403";
    }
    return route.page() ? "login" : "401";
  }

  private static String outcome(MockHttpServletResponse response) {
    var location = response.getRedirectedUrl();
    return switch (response.getStatus()) {
      case 401, 403 -> String.valueOf(response.getStatus());
      case 302 ->
          location != null && location.startsWith("/oauth2/authorization/") ? "login" : "permitido";
      default -> "permitido";
    };
  }

  /** The matrix must list every operation of the contract with the role the contract declares. */
  @Test
  void matrixCoversEveryContractOperationWithItsRole() throws IOException {
    Object spec;
    try (var in = getClass().getResourceAsStream("/openapi/radar-api.v1.yaml")) {
      spec = new Yaml().load(in);
    }
    var contract = new TreeSet<String>();
    if (spec instanceof Map<?, ?> root && root.get("paths") instanceof Map<?, ?> paths) {
      paths.forEach(
          (path, item) -> {
            if (item instanceof Map<?, ?> operations) {
              operations.forEach(
                  (method, operation) -> {
                    if (operation instanceof Map<?, ?> op
                        && op.get("x-required-role") instanceof String role) {
                      contract.add(
                          method.toString().toUpperCase(Locale.ROOT) + " " + path + " " + role);
                    }
                  });
            }
          });
    }
    var matrix = new TreeSet<String>();
    for (var route : ROUTES) {
      if (!route.page() && !route.label().startsWith("/actuator")) {
        matrix.add(
            route
                + " "
                + switch (route.needs()) {
                  case VISITOR -> "none";
                  case SUPPLIER -> "supplier";
                  case OPERATOR -> "operator";
                });
      }
    }

    assertThat(contract).hasSizeGreaterThan(10);
    assertThat(matrix).isEqualTo(contract);
  }

  @AfterAll
  static void matrixFileMatchesWhatTheApplicationAnswered() throws IOException {
    if (RESULTS.size() != ROUTES.size()
        || RESULTS.values().stream().anyMatch(row -> row.size() != Caller.values().length)) {
      return; // partial run (one case filtered): nothing to compare
    }
    var generated = render();
    var committed = Files.exists(MATRIX) ? Files.readString(MATRIX, StandardCharsets.UTF_8) : "";
    Files.createDirectories(MATRIX.getParent());
    Files.writeString(MATRIX, generated, StandardCharsets.UTF_8);
    assertThat(committed)
        .as("docs/security/authz-matrix.md estava desatualizado; o teste regravou, faça commit")
        .isEqualTo(generated);
  }

  private static String render() {
    var out = new StringBuilder();
    out.append(
        """
        # Matriz de autorização

        Arquivo gerado por `core/src/test/java/br/dev/miguelsanzbr/radar/identity/AuthorizationMatrixIT.java`, \
        que chama cada rota como cada tipo de usuário e registra o que a aplicação respondeu. Não \
        edite à mão: rode o teste e faça commit do arquivo regravado. O teste falha se o arquivo \
        versionado for diferente do gerado.

        - `permitido`: a requisição passou pela segurança (a resposta pode ser 2xx, 4xx de negócio \
        ou 404 de rota ainda não implementada).
        - `401`: sem credencial válida; resposta `application/problem+json` do tipo `unauthorized`.
        - `403`: papel insuficiente; nas rotas de API, problema do tipo `forbidden`.
        - `login`: página exige sessão; o navegador é redirecionado para o login OIDC.

        As rotas de API recebem um JWT (Bearer); as páginas, a sessão criada pelo login OIDC e o \
        token CSRF. O `supplier` da matriz já deu consentimento (`/assinatura`).

        | Rota | Canal | Visitante | supplier | operator |
        |---|---|---|---|---|
        """);
    RESULTS.forEach(
        (route, row) ->
            out.append(
                "| `%s` | %s | %s | %s | %s |\n"
                    .formatted(
                        route,
                        route.page() ? "página" : "API",
                        row.get(Caller.VISITOR),
                        row.get(Caller.SUPPLIER),
                        row.get(Caller.OPERATOR))));
    return out.toString();
  }
}
