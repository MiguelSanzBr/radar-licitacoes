package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class UfPropertiesTest {

  private static final Set<String> SIGLAS =
      Arrays.stream(Uf.values()).map(Enum::name).collect(Collectors.toSet());

  @Provide
  Arbitrary<String> twoLetters() {
    return Arbitraries.strings().withCharRange('A', 'z').ofLength(2);
  }

  @Property
  void acceptsExactlyTheSiglasInAnyCase(@ForAll("twoLetters") String letters) {
    var asKnown = SIGLAS.contains(letters.toUpperCase(Locale.ROOT));

    if (asKnown) {
      assertThat(Uf.of(letters)).isEqualTo(Uf.valueOf(letters.toUpperCase(Locale.ROOT)));
    } else {
      assertThatIllegalArgumentException().isThrownBy(() -> Uf.of(letters));
    }
  }

  @Property
  void everySiglaIsAcceptedInEveryCase(@ForAll("anyUf") Uf uf, @ForAll boolean lower) {
    var text = lower ? uf.name().toLowerCase(Locale.ROOT) : uf.name();

    assertThat(Uf.of(text)).isEqualTo(uf);
  }

  @Provide
  Arbitrary<Uf> anyUf() {
    return Arbitraries.of(Uf.class);
  }
}
