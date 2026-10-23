package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code Retry-After} header: a number of seconds, or an HTTP date in any of the three formats
 * of RFC 9110 (IMF-fixdate, the obsolete RFC 850 and asctime ones; always GMT).
 */
enum RetryAfter {
  ; // no constants: a holder of static methods that cannot be instantiated

  /** A wait longer than this is a mistake, and would also overflow the clock arithmetic. */
  private static final Duration MAX = Duration.ofDays(1);

  private static final List<DateTimeFormatter> DATES =
      List.of(
          DateTimeFormatter.RFC_1123_DATE_TIME,
          DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ENGLISH));

  /** The positive wait the header asks for, at most one day; empty when absent or unusable. */
  static Optional<Duration> parse(HttpHeaders headers, Instant now) {
    return headers
        .firstValue("Retry-After")
        .flatMap(value -> wait(value.trim(), now))
        .filter(Duration::isPositive)
        .map(wait -> wait.compareTo(MAX) > 0 ? MAX : wait);
  }

  private static Optional<Duration> wait(String value, Instant now) {
    if (value.matches("[0-9]+")) {
      try {
        return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
      } catch (NumberFormatException tooBigForALong) {
        return Optional.of(MAX);
      }
    }
    for (var format : DATES) {
      try {
        var date = Instant.from(format.withZone(ZoneOffset.UTC).parse(value));
        return Optional.of(Duration.between(now, date));
      } catch (DateTimeParseException notThisFormat) {
        // try the next one
      }
    }
    return Optional.empty();
  }
}
