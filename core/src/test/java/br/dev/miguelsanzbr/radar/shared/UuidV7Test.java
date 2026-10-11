package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

  @Test
  void layoutFollowsRfc9562() {
    var at = Instant.parse("2026-10-22T12:00:00.123Z");
    var randomBits = 0x0123456789ABCDEFL;
    var ids = new UuidV7(InstantSource.fixed(at), () -> randomBits);

    var uuid = ids.next();

    var expectedMsb = (at.toEpochMilli() << 16) | 0x7000L | 0x567L;
    var expectedLsb = 0x8000000000000000L | (randomBits & 0x3FFFFFFFFFFFFFFFL);
    assertThat(uuid).isEqualTo(new UUID(expectedMsb, expectedLsb));
  }

  @Test
  void versionAndVariantSurviveAllOnesAndAllZerosRandomness() {
    var at = InstantSource.fixed(Instant.parse("2026-10-22T12:00:00Z"));

    for (var bits : new long[] {0L, -1L}) {
      var uuid = new UuidV7(at, () -> bits).next();

      assertThat(uuid.version()).isEqualTo(7);
      assertThat(uuid.variant()).isEqualTo(2);
    }
  }
}
