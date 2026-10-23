package br.dev.miguelsanzbr.radar.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

  private static String b64(String plain) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aRankCursorIsTheVersionedCompactFormInBase64Url() {
    var cursor = new Cursor.ByRank(0.25, "A/1");

    assertThat(cursor.encode()).isEqualTo(b64("v1|r|0.25|A/1"));
    assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @Test
  void aPublishedCursorKeepsMicrosecondsBecauseTheColumnDoes() {
    var instant = Instant.parse("2026-10-20T12:00:00.123456Z");
    var cursor = new Cursor.ByPublished(instant, "A/1");

    assertThat(cursor.encode()).isEqualTo(b64("v1|p|1792497600123456|A/1"));
    assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @Test
  void theIdMayContainTheSeparator() {
    var cursor = new Cursor.ByRank(1.5, "a|b|c");

    assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "%%%", "a", "not base64!", "djF8cg"})
  void textThatIsNotACursorIsRejected(String text) {
    assertThatThrownBy(() -> Cursor.decode(text)).isInstanceOf(InvalidCursorException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "v2|r|0.5|id",
        "v1|x|0.5|id",
        "v1|r|0.5",
        "v1|r",
        "r|0.5|id",
        "v1|r|abc|id",
        "v1|r|NaN|id",
        "v1|r|Infinity|id",
        "v1|r|0.5| ",
        "v1|r|0.5|",
        "v1|p|abc|id",
        "v1|p|1.5|id",
        "v1|p|99999999999999999999|id",
        "v1|p|1000| "
      })
  void aWellEncodedButWrongPayloadIsRejected(String plain) {
    assertThatThrownBy(() -> Cursor.decode(b64(plain))).isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void aKeyThatCannotBeEncodedIsRejectedAtConstruction() {
    assertThatThrownBy(() -> new Cursor.ByRank(Double.NaN, "id"))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new Cursor.ByRank(Double.POSITIVE_INFINITY, "id"))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new Cursor.ByRank(1, null)).isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new Cursor.ByRank(1, " ")).isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new Cursor.ByPublished(null, "id"))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new Cursor.ByPublished(Instant.EPOCH, null))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new Cursor.ByPublished(Instant.EPOCH, ""))
        .isInstanceOf(InvalidCursorException.class);
  }
}
