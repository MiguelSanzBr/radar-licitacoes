package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class NGramModelTest {

  private static final List<String> TEXTS =
      List.of(
          "contratação de empresa para serviços de limpeza",
          "aquisição de material de limpeza para escolas",
          "contratação de serviços de manutenção de escolas",
          "  ",
          "aquisição de empresa especializada");

  @Test
  void generatesOnlyTokensOfTheTrainingTexts() {
    var model = NGramModel.train(TEXTS);
    var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(3L);
    var known = new HashSet<String>();
    TEXTS.forEach(t -> known.addAll(List.of(t.strip().split("\\s+"))));
    var novel = new HashSet<String>();
    for (int i = 0; i < 500; i++) {
      var text = model.generate(rng);
      assertThat(text).isNotBlank();
      assertThat(List.of(text.split(" "))).isSubsetOf(known);
      novel.add(text);
    }
    // why: the backoff to bigrams recombines the texts, so the output is not just the corpus
    assertThat(novel).hasSizeGreaterThan(TEXTS.size());
  }

  @Test
  void neverGoesBeyondTheLongestTrainingText() {
    var model = NGramModel.train(List.of("a a a a a a a a"));
    var rng = RandomGeneratorFactory.of("L64X128MixRandom").create(5L);
    int longest = 0;
    for (int i = 0; i < 300; i++) {
      longest = Math.max(longest, model.generate(rng).split(" ").length);
    }
    assertThat(longest).isEqualTo(8);
  }

  @Test
  void sameSeedSameText() {
    var model = NGramModel.train(TEXTS);
    var first = model.generate(RandomGeneratorFactory.of("L64X128MixRandom").create(9L));
    var second = model.generate(RandomGeneratorFactory.of("L64X128MixRandom").create(9L));
    assertThat(second).isEqualTo(first);
  }
}
