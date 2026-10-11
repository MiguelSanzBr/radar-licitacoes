package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class UuidV7PropertiesTest {

  private static final long MAX_48_BITS = (1L << 48) - 1;

  @Provide
  Arbitrary<Long> epochMillis() {
    return Arbitraries.longs().between(0, MAX_48_BITS);
  }

  @Property
  void versionVariantAndTimestampAreAlwaysRight(
      @ForAll("epochMillis") long millis, @ForAll long seed) {
    var ids = new UuidV7(InstantSource.fixed(Instant.ofEpochMilli(millis)), new Random(seed));

    var uuid = ids.next();

    assertThat(uuid.version()).isEqualTo(7);
    assertThat(uuid.variant()).isEqualTo(2);
    assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(millis);
  }
}
