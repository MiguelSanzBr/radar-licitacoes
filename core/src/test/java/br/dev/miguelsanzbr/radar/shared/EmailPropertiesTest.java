package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class EmailPropertiesTest {

  @Provide
  Arbitrary<String> rawEmails() {
    var part = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20);
    return Combinators.combine(part, part, part)
        .as((local, domain, tld) -> local + "@" + domain + "." + tld);
  }

  @Property
  void normalizationIsIdempotent(@ForAll("rawEmails") String raw) {
    var email = new Email(raw);

    assertThat(new Email(email.value())).isEqualTo(email);
  }

  @Property
  void equalityIgnoresCase(@ForAll("rawEmails") String raw) {
    assertThat(new Email(raw.toUpperCase(Locale.ROOT)))
        .isEqualTo(new Email(raw.toLowerCase(Locale.ROOT)));
  }
}
