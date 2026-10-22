package br.dev.miguelsanzbr.radar.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class VerificationTokenTest {

  @Test
  void rawTokenIsThirtyTwoRandomBytesInBase64UrlWithoutPadding() {
    var raw = VerificationToken.newRaw(RandomGenerator.of("L64X128MixRandom"));

    assertThat(raw).matches("[A-Za-z0-9_-]{43}");
    assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
  }

  @Test
  void twoTokensDiffer() {
    var random = RandomGenerator.of("L64X128MixRandom");

    assertThat(VerificationToken.newRaw(random)).isNotEqualTo(VerificationToken.newRaw(random));
  }

  @Test
  void hashIsSha256OfTheRawText() {
    assertThat(HexFormat.of().formatHex(VerificationToken.hash("abc")))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void timeToLiveIsFortyEightHours() {
    assertThat(VerificationToken.TTL).isEqualTo(Duration.ofHours(48));
  }
}
