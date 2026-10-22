package br.dev.miguelsanzbr.radar.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Sha256Test {

  @Test
  void hashesUtf8Text() {
    assertThat(HexFormat.of().formatHex(Sha256.hash("")))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void unknownAlgorithmFailsLoudly() {
    assertThatThrownBy(() -> Sha256.digest("NOPE", "x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOPE");
  }
}
