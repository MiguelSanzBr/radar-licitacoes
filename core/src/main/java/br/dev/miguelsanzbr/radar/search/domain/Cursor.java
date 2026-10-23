package br.dev.miguelsanzbr.radar.search.domain;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Opaque keyset position of a page: the sort key of the last row returned. Encoded as base64url
 * without padding of {@code v1|r|<rank>|<id>} (by relevance) or {@code v1|p|<epochMicros>|<id>} (by
 * publication). It carries public sort keys only, so it is not signed: tampering can at worst skip
 * results the client could ask for anyway. Microseconds, not milliseconds, because that is the
 * precision of the column and a coarser key would skip rows that share a millisecond.
 */
public sealed interface Cursor {

  /** The column holds timestamptz: years 1 to 9999. */
  Instant FIRST = Instant.parse("0001-01-01T00:00:00Z");

  Instant LAST = Instant.parse("9999-12-31T23:59:59.999999Z");

  String id();

  /** Kind tag and sort key as they appear in the encoded form, for example {@code r|0.25}. */
  String key();

  default String encode() {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("v1|" + key() + "|" + id()).getBytes(StandardCharsets.UTF_8));
  }

  record ByRank(double rank, String id) implements Cursor {
    public ByRank {
      if (!Double.isFinite(rank)) {
        throw new InvalidCursorException("rank is not finite");
      }
      CursorIds.requireUsable(id);
    }

    @Override
    public String key() {
      return "r|" + rank;
    }
  }

  record ByPublished(Instant publishedAt, String id) implements Cursor {
    public ByPublished {
      if (publishedAt == null) {
        throw new InvalidCursorException("publication date is missing");
      }
      if (publishedAt.isBefore(FIRST) || publishedAt.isAfter(LAST)) {
        throw new InvalidCursorException("publication date is outside years 1 to 9999");
      }
      CursorIds.requireUsable(id);
    }

    @Override
    public String key() {
      return "p|" + ChronoUnit.MICROS.between(Instant.EPOCH, publishedAt);
    }
  }

  /** Throws {@link InvalidCursorException} for anything that is not a cursor of this version. */
  static Cursor decode(String text) {
    if (text == null) {
      throw new InvalidCursorException("missing");
    }
    try {
      var plain = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
      var parts = plain.split("\\|", 4);
      if (parts.length != 4 || !parts[0].equals("v1")) {
        throw new InvalidCursorException("unknown version or shape");
      }
      return switch (parts[1]) {
        case "r" -> new ByRank(Double.parseDouble(parts[2]), parts[3]);
        case "p" ->
            new ByPublished(
                Instant.EPOCH.plus(Long.parseLong(parts[2]), ChronoUnit.MICROS), parts[3]);
        default -> throw new InvalidCursorException("unknown kind");
      };
    } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
      throw new InvalidCursorException("malformed");
    }
  }
}
