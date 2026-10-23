package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterTest {

  private static final Instant NOW = Instant.parse("2026-10-21T07:00:00Z");

  private static HttpHeaders headers(String name, String value) {
    return HttpHeaders.of(Map.of(name, List.of(value)), (a, b) -> true);
  }

  private static Optional<Duration> parse(String value) {
    return RetryAfter.parse(headers("Retry-After", value), NOW);
  }

  @Test
  void secondsAreTakenAsTheyCome() {
    assertThat(parse("120")).contains(Duration.ofMinutes(2));
    assertThat(parse(" 7 ")).contains(Duration.ofSeconds(7));
  }

  @Test
  void aHttpDateIsTheTimeFromNowUntilThatDate() {
    assertThat(parse("Wed, 21 Oct 2026 07:28:00 GMT")).contains(Duration.ofMinutes(28));
  }

  @Test
  void aHttpDateInThePastIsNotUsable() {
    assertThat(parse("Wed, 21 Oct 2026 06:00:00 GMT")).isEmpty();
  }

  @Test
  void aMissingHeaderIsNotUsable() {
    assertThat(RetryAfter.parse(headers("Other", "10"), NOW)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"soon", "", "0", "-5", "1.5", "Tomorrow, 25 Oct 2026 07:00:00 GMT"})
  void garbageAndNonPositiveValuesAreNotUsable(String value) {
    assertThat(parse(value)).isEmpty();
  }

  @Test
  void absurdWaitsAreCappedAtOneDay() {
    assertThat(parse("99999999999")).contains(Duration.ofDays(1));
    assertThat(parse("Fri, 21 Oct 2033 07:00:00 GMT")).contains(Duration.ofDays(1));
  }

  @Test
  void anAllDigitsValueTooBigForALongIsTheCap() {
    assertThat(parse("99999999999999999999999")).contains(Duration.ofDays(1));
  }

  @Test
  void theObsoleteHttpDateFormatsAreAcceptedAsWell() {
    assertThat(parse("Wednesday, 21-Oct-26 07:28:00 GMT")).contains(Duration.ofMinutes(28));
    assertThat(parse("Wed Oct 21 07:28:00 2026")).contains(Duration.ofMinutes(28));
    // asctime pads a one digit day with a space
    assertThat(parse("Wed Nov  4 07:00:00 2026")).contains(Duration.ofDays(1));
  }
}
