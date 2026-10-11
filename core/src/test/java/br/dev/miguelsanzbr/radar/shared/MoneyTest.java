package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void rejectsNullAmount() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Money(null));
  }

  @Test
  void rejectsNullAndMalformedText() {
    assertThatIllegalArgumentException().isThrownBy(() -> Money.of((String) null));
    assertThatIllegalArgumentException().isThrownBy(() -> Money.of("abc"));
  }

  @Test
  void normalizesScaleToTwo() {
    assertThat(Money.of("1").amount()).isEqualTo(new BigDecimal("1.00"));
    assertThat(new Money(new BigDecimal("1.5000")).amount().scale()).isEqualTo(2);
  }

  @Test
  void roundsHalfEven() {
    assertThat(Money.of("0.125").amount()).isEqualTo(new BigDecimal("0.12"));
    assertThat(Money.of("0.135").amount()).isEqualTo(new BigDecimal("0.14"));
    assertThat(Money.of("0.126").amount()).isEqualTo(new BigDecimal("0.13"));
  }

  @Test
  void equalityIgnoresInputScale() {
    assertThat(Money.of("1.0")).isEqualTo(Money.of("1.00")).hasSameHashCodeAs(Money.of("1.00"));
  }

  @Test
  void zeroIsZero() {
    assertThat(Money.ZERO).isEqualTo(Money.of("0.00")).isEqualTo(Money.of(0));
  }

  @Test
  void buildsFromCents() {
    assertThat(Money.of(12345)).isEqualTo(Money.of("123.45"));
    assertThat(Money.of(-5)).isEqualTo(Money.of("-0.05"));
  }

  @Test
  void adds() {
    assertThat(Money.of("1.10").plus(Money.of("2.25"))).isEqualTo(Money.of("3.35"));
  }

  @Test
  void detectsNegative() {
    assertThat(Money.of("-0.01").isNegative()).isTrue();
    assertThat(Money.ZERO.isNegative()).isFalse();
    assertThat(Money.of("0.01").isNegative()).isFalse();
  }

  @Test
  void comparesByAmount() {
    assertThat(Money.of("1.00")).isLessThan(Money.of("1.01")).isGreaterThan(Money.of("0.99"));
    assertThat(Money.of("1.0").compareTo(Money.of("1.00"))).isZero();
  }

  @Test
  void centsAreExact() {
    assertThat(Money.of("123.45").cents()).isEqualTo(12345L);
    assertThat(Money.of(Long.MAX_VALUE).cents()).isEqualTo(Long.MAX_VALUE);
    assertThat(Money.of(Long.MIN_VALUE).cents()).isEqualTo(Long.MIN_VALUE);
  }

  @Test
  void centsThrowOnOverflow() {
    var tooBig = Money.of(Long.MAX_VALUE).plus(Money.of(1));

    assertThatThrownBy(tooBig::cents).isInstanceOf(ArithmeticException.class);
  }
}
