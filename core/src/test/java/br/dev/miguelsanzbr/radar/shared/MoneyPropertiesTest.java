package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class MoneyPropertiesTest {

  @Provide
  Arbitrary<Money> money() {
    return Arbitraries.longs().between(-1_000_000_000_000L, 1_000_000_000_000L).map(Money::of);
  }

  @Property
  void additionIsCommutative(@ForAll("money") Money a, @ForAll("money") Money b) {
    assertThat(a.plus(b)).isEqualTo(b.plus(a));
  }

  @Property
  void additionIsAssociative(
      @ForAll("money") Money a, @ForAll("money") Money b, @ForAll("money") Money c) {
    assertThat(a.plus(b).plus(c)).isEqualTo(a.plus(b.plus(c)));
  }

  @Property
  void zeroIsTheIdentity(@ForAll("money") Money a) {
    assertThat(a.plus(Money.ZERO)).isEqualTo(a);
  }

  @Property
  void compareToIsConsistentWithEquals(@ForAll("money") Money a, @ForAll("money") Money b) {
    assertThat(a.compareTo(b) == 0).isEqualTo(a.equals(b));
  }

  @Property
  void centsRoundTrip(@ForAll long cents) {
    assertThat(Money.of(cents).cents()).isEqualTo(cents);
  }
}
