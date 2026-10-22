package br.dev.miguelsanzbr.radar.api;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Valida requisição e resposta de um teste MockMvc contra o contrato OpenAPI v1.
 *
 * <pre>{@code
 * mockMvc.perform(get("/api/v1/procurements")).andExpect(OpenApiConformance.conforms());
 * }</pre>
 */
public final class OpenApiConformance {

  private static final String SPEC = "/openapi/radar-api.v1.yaml";

  // O YAML entra como conteudo: o validador nao le URL jar: (classpath de verify, com o jar de
  // contracts).
  // Os testes autenticam com jwt()/oidcLogin(), sem cabecalho Authorization; a cadeia de
  // seguranca tem testes proprios (SecurityConfigTest e a matriz de autorizacao).
  private static final ResultMatcher CONFORMS =
      openApi()
          .isValid(
              OpenApiInteractionValidator.createForInlineApiSpecification(readSpec())
                  .withLevelResolver(
                      LevelResolver.create()
                          .withLevel(
                              "validation.request.security.missing", ValidationReport.Level.IGNORE)
                          .build())
                  .build());

  private static String readSpec() {
    try (InputStream in = OpenApiConformance.class.getResourceAsStream(SPEC)) {
      return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private OpenApiConformance() {}

  public static ResultMatcher conforms() {
    return CONFORMS;
  }
}
