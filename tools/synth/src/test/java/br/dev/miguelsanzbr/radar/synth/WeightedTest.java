package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class WeightedTest {

  @Test
  void picksInProportionToTheSamplesAndKeepsNull() {
    var weights = new LinkedHashMap<String, Long>();
    weights.put("a", 700L);
    weights.put("b", 200L);
    weights.put(null, 100L);
    var weighted = new Weighted<>(weights);
    var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(1L);
    var counts = new HashMap<String, Integer>();
    for (int i = 0; i < 10_000; i++) {
      counts.merge(weighted.pick(rng), 1, Integer::sum);
    }
    assertThat(counts.get("a")).isBetween(6_700, 7_300);
    assertThat(counts.get("b")).isBetween(1_700, 2_300);
    assertThat(counts.get(null)).isBetween(700, 1_300);
  }

  @Test
  void aSingleSampleIsAlwaysPicked() {
    var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(1L);
    assertThat(new Weighted<>(Map.of(7, 1L)).pick(rng)).isEqualTo(7);
  }
}
