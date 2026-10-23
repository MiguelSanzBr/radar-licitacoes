package br.dev.miguelsanzbr.radar.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final UUID ID = UUID.fromString("0198a3a0-0000-7000-8000-000000000001");

  private static ApiKey.Issued issue() {
    return ApiKey.issue(ID, "kc-1", Tier.FREE, NOW, RandomGenerator.of("L64X128MixRandom"));
  }

  @Test
  void theKeyHasTheDocumentedShape() {
    var issued = issue();

    assertThat(issued.secret()).matches("rk_[0-9a-f]{8}_[A-Za-z0-9_-]{43}");
    assertThat(issued.key().prefix()).isEqualTo(issued.secret().substring(3, 11));
    assertThat(issued.key().ownerSubject()).isEqualTo("kc-1");
    assertThat(issued.key().tier()).isEqualTo(Tier.FREE);
    assertThat(issued.key().createdAt()).isEqualTo(NOW);
    assertThat(issued.key().id()).isEqualTo(ID);
  }

  @Test
  void onlyTheHashIsKeptNeverTheSecret() {
    var issued = issue();

    assertThat(issued.key().keyHash())
        .matches("[0-9a-f]{64}")
        .isEqualTo(ApiKey.hash(issued.secret()));
    assertThat(issued.key().toString()).doesNotContain(issued.secret());
    assertThat(issued.key().toString()).doesNotContain(issued.secret().substring(12));
  }

  @Test
  void everyKeyIsDifferent() {
    var random = RandomGenerator.of("L64X128MixRandom");

    var first = ApiKey.issue(ID, "kc-1", Tier.FREE, NOW, random);
    var second = ApiKey.issue(ID, "kc-1", Tier.FREE, NOW, random);

    assertThat(first.secret()).isNotEqualTo(second.secret());
    assertThat(first.key().keyHash()).isNotEqualTo(second.key().keyHash());
  }

  @Test
  void hashIsSha256InHex() {
    assertThat(ApiKey.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void unknownAlgorithmFailsLoudly() {
    assertThatThrownBy(() -> ApiKey.digest("NOPE", "x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOPE");
  }

  @Test
  void theSecretNeverShowsInToString() {
    var issued = issue();

    assertThat(issued.toString())
        .doesNotContain(issued.secret())
        .doesNotContain(issued.secret().substring(12))
        .contains(issued.key().prefix())
        .contains("redacted");
  }

  @Test
  void everyIssuedSecretIsWellFormed() {
    assertThat(ApiKey.wellFormed(issue().secret())).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "rk_",
        "rk_abcd1234_short",
        "rk_ABCD1234_0123456789012345678901234567890123456789012",
        "rk_abcd123_0123456789012345678901234567890123456789012",
        "xx_abcd1234_0123456789012345678901234567890123456789012",
        "rk_abcd1234_01234567890123456789012345678901234567890123",
        "rk_abcd1234_012345678901234567890123456789012345678901+/",
        "rk_abcd1234_0123456789012345678901234567890123456789012\n"
      })
  void anythingOtherThanTheIssuedShapeIsNotAKey(String value) {
    assertThat(ApiKey.wellFormed(value)).isFalse();
  }
}
