package br.dev.miguelsanzbr.radar.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StatsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void percentileUsesNearestRank() {
    List<Long> v = List.of(15L, 20L, 35L, 40L, 50L);

    assertThat(Stats.percentile(v, 30)).isEqualTo(20L);
    assertThat(Stats.percentile(v, 40)).isEqualTo(20L);
    assertThat(Stats.percentile(v, 50)).isEqualTo(35L);
    assertThat(Stats.percentile(v, 100)).isEqualTo(50L);
    assertThat(Stats.percentile(v, 0)).isEqualTo(15L);
  }

  @Test
  void percentileIgnoresInputOrderAndNeverMutatesIt() {
    List<Long> v = new ArrayList<>(List.of(9L, 1L, 5L));

    assertThat(Stats.percentile(v, 50)).isEqualTo(5L);
    assertThat(v).containsExactly(9L, 1L, 5L);
  }

  @Test
  void percentileProperties() {
    Random rnd = new Random(42);
    for (int run = 0; run < 200; run++) {
      List<Long> v = new ArrayList<>();
      for (int i = 0, n = 1 + rnd.nextInt(60); i < n; i++) {
        v.add((long) rnd.nextInt(1000));
      }
      long p50 = Stats.percentile(v, 50);
      long p95 = Stats.percentile(v, 95);
      long p99 = Stats.percentile(v, 99);

      assertThat(p50).isLessThanOrEqualTo(p95);
      assertThat(p95).isLessThanOrEqualTo(p99);
      assertThat(v).contains(p50, p95, p99);
      assertThat(p99).isBetween(Collections.min(v), Collections.max(v));
      assertThat(Stats.percentile(v, 100)).isEqualTo(Collections.max(v));
    }
  }

  @Test
  void percentileRejectsEmptyInput() {
    assertThatThrownBy(() -> Stats.percentile(List.of(), 50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fieldStatsCountsPresentAndNullAtTopLevelAndOneLevelDeep() {
    JsonNode items =
        JSON.readTree(
            """
            [{"a":1,"b":null,"o":{"x":"v","y":null},"arr":[1]},
             {"a":2,"o":{"x":"w"}}]
            """);

    JsonNode stats = Stats.fieldStats(items);

    assertThat(stats.path("a").toString()).isEqualTo("{\"present\":2,\"null\":0}");
    assertThat(stats.path("b").toString()).isEqualTo("{\"present\":0,\"null\":2}");
    assertThat(stats.path("o").path("present").asInt()).isEqualTo(2);
    assertThat(stats.path("o.x").path("present").asInt()).isEqualTo(2);
    assertThat(stats.path("o.y").path("null").asInt()).isEqualTo(2);
    assertThat(stats.path("arr").path("present").asInt()).isEqualTo(1);
    assertThat(stats.path("arr").path("null").asInt()).isEqualTo(1);
  }

  @Test
  void itemsReadsDataArrayRootArrayOrNothing() {
    assertThat(Stats.items(JSON.readTree("{\"data\":[1,2]}"))).hasSize(2);
    assertThat(Stats.items(JSON.readTree("[1,2,3]"))).hasSize(3);
    assertThat(Stats.items(JSON.readTree("{\"data\":5}")).isArray()).isFalse();
    assertThat(Stats.items(JSON.readTree("")).isArray()).isFalse();
  }

  @Test
  void cpfLikeFlagsCpfNamedFieldsAndElevenDigitIdentifiers() {
    assertThat(Stats.cpfLike(JSON.readTree("{\"cpf\":\"123\"}"))).isEqualTo(1);
    assertThat(Stats.cpfLike(JSON.readTree("{\"cpfResponsavel\":null}"))).isZero();
    assertThat(Stats.cpfLike(JSON.readTree("{\"niFornecedor\":\"12345678901\"}"))).isEqualTo(1);
    assertThat(Stats.cpfLike(JSON.readTree("{\"niFornecedor\":\"12345678000142\"}"))).isZero();
    assertThat(Stats.cpfLike(JSON.readTree("{\"niFornecedor\":12345678901}"))).isZero();
    assertThat(Stats.cpfLike(JSON.readTree("{\"cnpj\":\"12345678901\"}"))).isZero();
    assertThat(
            Stats.cpfLike(JSON.readTree("{\"data\":[{\"cpf\":\"1\"},{\"x\":1},{\"cpf\":\"2\"}]}")))
        .isEqualTo(2);
    assertThat(Stats.cpfLike(JSON.readTree("[{\"niFornecedor\":\"12345678901\"}]"))).isEqualTo(1);
    assertThat(Stats.cpfLike(JSON.readTree("\"text\""))).isZero();
  }

  @Test
  void sha256IsStableAndRejectsUnknownAlgorithms() {
    assertThat(Hashes.sha256("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThatThrownBy(() -> Hashes.hex("NOPE", "abc")).isInstanceOf(IllegalStateException.class);
  }
}
