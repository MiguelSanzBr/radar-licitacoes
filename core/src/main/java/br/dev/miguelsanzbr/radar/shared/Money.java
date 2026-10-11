package br.dev.miguelsanzbr.radar.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amount in BRL, always at scale 2 so equality ignores the scale of the input. */
public record Money(BigDecimal amount) implements Comparable<Money> {

  public static final Money ZERO = new Money(BigDecimal.ZERO);

  public Money {
    if (amount == null) {
      throw new IllegalArgumentException("amount must not be null");
    }
    amount = amount.setScale(2, RoundingMode.HALF_EVEN);
  }

  public static Money of(String text) {
    return new Money(text == null ? null : new BigDecimal(text));
  }

  public static Money of(long cents) {
    return new Money(BigDecimal.valueOf(cents, 2));
  }

  public Money plus(Money other) {
    return new Money(amount.add(other.amount));
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  /** Exact amount in cents; throws {@link ArithmeticException} when it does not fit a long. */
  public long cents() {
    return amount.movePointRight(2).longValueExact();
  }

  @Override
  public int compareTo(Money other) {
    return amount.compareTo(other.amount);
  }
}
