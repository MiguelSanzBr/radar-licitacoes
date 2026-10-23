package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityProblemAdviceTest {

  private final IdentityProblemAdvice advice = new IdentityProblemAdvice();

  private static ConstraintViolationException violationAt(String path) {
    ConstraintViolation<?> violation = mock(ConstraintViolation.class);
    var property = mock(Path.class);
    when(property.toString()).thenReturn(path);
    when(violation.getPropertyPath()).thenReturn(property);
    return new ConstraintViolationException(Set.of(violation));
  }

  @Test
  void aViolationOfThePrefixNamesNoKey() {
    var response = advice.invalid(violationAt("revokeApiKey.prefix"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody().getType().toString()).endsWith("not-found.md");
  }

  @Test
  void anyOtherViolationIsA400ThatDoesNotEchoTheFrameworkMessage() {
    var response = advice.invalid(violationAt("revokeApiKey.other"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().getType().toString()).endsWith("validation.md");
    assertThat(response.getBody().getDetail()).isEqualTo("Um ou mais parâmetros são inválidos.");
  }
}
