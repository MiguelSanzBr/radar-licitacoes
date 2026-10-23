package br.dev.miguelsanzbr.radar.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class CursorPropertiesTest {

  @Provide
  Arbitrary<Double> anyFiniteDouble() {
    return Arbitraries.oneOf(
        Arbitraries.doubles().filter(Double::isFinite),
        Arbitraries.of(0.0, -0.0, Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 1e-7));
  }

  @Provide
  Arbitrary<Instant> microInstants() {
    return Arbitraries.longs()
        .between(-4_000_000_000_000_000L, 4_000_000_000_000_000L)
        .map(micros -> Instant.EPOCH.plus(micros, ChronoUnit.MICROS));
  }

  @Provide
  Arbitrary<String> ids() {
    return Arbitraries.strings()
        .alpha()
        .numeric()
        .withChars('|', '/', '-', '.', ' ')
        .ofMinLength(1)
        .ofMaxLength(40)
        .filter(id -> !id.isBlank());
  }

  @Property
  void aRankCursorSurvivesTheRoundTrip(
      @ForAll("anyFiniteDouble") double rank, @ForAll("ids") String id) {
    var cursor = new Cursor.ByRank(rank, id);

    assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @Property
  void aPublishedCursorSurvivesTheRoundTrip(
      @ForAll("microInstants") Instant at, @ForAll("ids") String id) {
    var cursor = new Cursor.ByPublished(at, id);

    assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @Property
  void aCursorIsAlwaysUrlSafeWithoutPadding(@ForAll("ids") String id, @ForAll double rank) {
    var encoded = new Cursor.ByRank(Double.isFinite(rank) ? rank : 0, id).encode();

    assertThat(encoded).matches("[A-Za-z0-9_-]+");
  }

  @Property
  void anyTextEitherDecodesOrFailsWithTheDomainException(@ForAll String text) {
    decodesOrFails(text);
  }

  @Property
  void anyBase64OfAnyTextEitherDecodesOrFailsWithTheDomainException(@ForAll String plain) {
    decodesOrFails(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
  }

  @Property
  void aMutatedValidCursorEitherDecodesOrFailsWithTheDomainException(
      @ForAll("ids") String id, @ForAll int position, @ForAll char replacement) {
    var valid = new Cursor.ByRank(1.5, id).encode();
    var at = Math.floorMod(position, valid.length());

    decodesOrFails(valid.substring(0, at) + replacement + valid.substring(at + 1));
  }

  private static void decodesOrFails(String text) {
    try {
      var cursor = Cursor.decode(text);
      assertThat(cursor).isNotNull();
      assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
    } catch (InvalidCursorException expected) {
      assertThat(expected).hasMessageContaining("cursor");
    }
  }
}
