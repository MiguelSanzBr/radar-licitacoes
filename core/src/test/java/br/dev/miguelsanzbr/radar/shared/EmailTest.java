package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailTest {

  @Test
  void trimsAndLowerCases() {
    assertThat(new Email("  Ana.Silva@Example.COM ").value()).isEqualTo("ana.silva@example.com");
  }

  @Test
  void acceptsSubdomains() {
    assertThat(new Email("a@mail.example.com.br").value()).isEqualTo("a@mail.example.com.br");
  }

  @Test
  void rejectsNull() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Email(null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "plain",
        "@example.com",
        "a@",
        "a@example",
        "a@@example.com",
        "a@b@example.com",
        "a b@example.com",
        "a@exa mple.com",
        "a@.com",
        "a@example.",
        "a@example..com"
      })
  void rejectsInvalidValuesNamingThem(String bad) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Email(bad))
        .withMessageContaining("[" + bad.trim().toLowerCase() + "]");
  }

  @Test
  void enforcesTheMaximumLengthOf254() {
    var suffix = "@example.com";
    var atLimit = "a".repeat(254 - suffix.length()) + suffix;
    var overLimit = "a".repeat(255 - suffix.length()) + suffix;

    assertThat(new Email(atLimit).value()).hasSize(254);
    assertThatIllegalArgumentException().isThrownBy(() -> new Email(overLimit));
  }
}
