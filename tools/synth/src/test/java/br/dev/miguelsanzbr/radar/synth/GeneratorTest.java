package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GeneratorTest {

  private static final Pattern ID = Pattern.compile("^SYN-\\d{14}-1-\\d{6}/\\d{4}$");
  private static Corpus corpus;

  @BeforeAll
  static void load() {
    corpus = Corpus.load(List.of(Fixtures.SEED));
  }

  private static List<Synthetic> rows(Corpus corpus, long seed, int count) {
    var generator = new Generator(corpus, seed, count);
    var rows = new ArrayList<Synthetic>();
    for (int i = 0; i < count; i++) {
      rows.add(generator.next());
    }
    return rows;
  }

  @Test
  void everyIdIsSyntheticUniqueAndNeverARealOne() {
    var real = new HashSet<String>();
    Fixtures.realRecords().forEach(r -> real.add(r.get("numeroControlePNCP").asString()));
    var seen = new HashSet<String>();

    for (var row : rows(corpus, 42, 20_000)) {
      assertThat(row.id()).matches(ID);
      assertThat(real).doesNotContain(row.id());
      assertThat(seen.add(row.id())).as(row.id()).isTrue();
    }
  }

  @Test
  void sameSeedSameRowsAndAnotherSeedOther() {
    assertThat(rows(corpus, 7, 300)).isEqualTo(rows(corpus, 7, 300));
    assertThat(rows(corpus, 7, 300)).isNotEqualTo(rows(corpus, 8, 300));
  }

  @Test
  void publicationDatesSpreadOverTheLastTwelveMonthsOfTheCorpus() {
    var latest =
        Fixtures.realRecords().stream()
            .map(r -> LocalDateTime.parse(r.get("dataPublicacaoPncp").asString()))
            .max(LocalDateTime::compareTo)
            .orElseThrow()
            .toInstant(ZoneOffset.UTC);
    var start = latest.minus(Duration.ofDays(365));
    var rows = rows(corpus, 42, 12_000);
    var buckets = new HashSet<Long>();
    Instant previous = start;

    for (var row : rows) {
      assertThat(row.publishedAt()).isBetween(start, latest).isAfterOrEqualTo(previous);
      assertThat(row.pncpUpdatedAt()).isAfterOrEqualTo(row.publishedAt());
      previous = row.publishedAt();
      buckets.add(Duration.between(start, row.publishedAt()).toDays() / 30);
    }
    assertThat(buckets).hasSize(13);
  }

  @Test
  void objectsUseOnlyKnownTokensAndKeepTheMeanLength() {
    var tokens = new HashSet<String>();
    double realTotal = 0;
    var real = Fixtures.realRecords();
    for (var record : real) {
      var words = record.get("objetoCompra").asString().strip().split("\\s+");
      tokens.addAll(List.of(words));
      realTotal += words.length;
    }
    var rows = rows(corpus, 42, 5_000);
    double total = 0;

    for (var row : rows) {
      var words = row.objectDescription().split(" ");
      assertThat(List.of(words)).isSubsetOf(tokens);
      total += words.length;
    }
    assertThat(total / rows.size())
        .isBetween(0.7 * realTotal / real.size(), 1.3 * realTotal / real.size());
  }

  @Test
  void organsAndUnitsComeFromAPoolSoTheyRepeat() {
    var rows = rows(corpus, 42, 6_000);
    assertThat(rows.stream().map(Synthetic::organName).distinct().count()).isLessThan(2_001);
    assertThat(rows.stream().map(Synthetic::organCnpj).distinct().count()).isGreaterThan(1_000);
  }

  @Test
  void statusFollowsTheSituationAndDefaultsToPublished() {
    var statuses = new HashSet<String>();
    for (var row : rows(Corpus.load(List.of(Fixtures.SEED)), 3, 20_000)) {
      statuses.add(row.situation().id() + ":" + row.status());
    }
    assertThat(statuses).contains("1:PUBLISHED", "4:SUSPENDED", "2:REVOKED", "3:ANNULLED");
    assertThat(Synthetic.statusOf(99)).isEqualTo("PUBLISHED");
  }

  @Test
  void valuesFollowTheCorpusAndFillTheBands() {
    var bands = new HashSet<Short>();
    for (var row : rows(corpus, 42, 20_000)) {
      if (row.estimatedValue() != null) {
        assertThat(row.estimatedValue().scale()).isEqualTo(2);
      }
      bands.add(row.valueBand());
    }
    assertThat(bands).contains((short) 1, (short) 2, (short) 3);
  }

  @Test
  void valueBandsAreTheOnesOfTheReadModel() {
    assertThat(band(null)).isEqualTo((short) 0);
    assertThat(band("49999.99")).isEqualTo((short) 1);
    assertThat(band("50000.00")).isEqualTo((short) 2);
    assertThat(band("499999.99")).isEqualTo((short) 2);
    assertThat(band("500000.00")).isEqualTo((short) 3);
    assertThat(band("4999999.99")).isEqualTo((short) 3);
    assertThat(band("5000000.00")).isEqualTo((short) 4);
  }

  private static short band(String value) {
    return Synthetic.bandOf(value == null ? null : new BigDecimal(value));
  }

  @Test
  void absentValuesWindowsAndPlacesAreGeneratedToo() {
    var rows = rows(Corpus.load(List.of(Fixtures.EDGE)), 5, 500);
    Set<String> flags = new HashSet<>();
    for (var row : rows) {
      flags.add(row.estimatedValue() == null ? "noValue" : "value");
      flags.add(row.openingAt() == null ? "noWindow" : "window");
      flags.add(row.uf() == null ? "noUf" : "uf");
      flags.add(row.disputeMode() == null ? "noDispute" : "dispute");
      flags.add(row.legalBasis() == null ? "noLegal" : "legal");
      if (row.estimatedValue() == null) {
        assertThat(row.valueBand()).isZero();
      }
    }
    assertThat(flags)
        .contains("noValue", "value", "noWindow", "window", "noUf", "uf", "noDispute", "dispute")
        .contains("noLegal", "legal");
  }
}
