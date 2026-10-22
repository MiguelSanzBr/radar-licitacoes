package br.dev.miguelsanzbr.radar.synth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

/**
 * Word trigrams with backoff: the next word follows the last two, and once in a while only the last
 * one, which recombines the texts instead of replaying them. Words are whitespace separated tokens,
 * so a generated text only ever holds tokens of the corpus.
 */
final class NGramModel {

  private static final String START = "\u0002";
  private static final String END = "\u0003";
  private static final double BACKOFF = 0.15;

  private record Pair(String first, String second) {}

  private final Map<Pair, Weighted<String>> trigrams = new LinkedHashMap<>();
  private final Map<String, Weighted<String>> bigrams = new LinkedHashMap<>();
  private final int maxTokens;

  private NGramModel(
      Map<Pair, Map<String, Long>> trigramCounts,
      Map<String, Map<String, Long>> bigramCounts,
      int maxTokens) {
    trigramCounts.forEach((key, counts) -> trigrams.put(key, new Weighted<>(counts)));
    bigramCounts.forEach((key, counts) -> bigrams.put(key, new Weighted<>(counts)));
    this.maxTokens = maxTokens;
  }

  static NGramModel train(Collection<String> texts) {
    var trigramCounts = new LinkedHashMap<Pair, Map<String, Long>>();
    var bigramCounts = new LinkedHashMap<String, Map<String, Long>>();
    int longest = 0;
    for (var text : texts.stream().filter(Predicate.not(String::isBlank)).toList()) {
      var words = text.strip().split("\\s+");
      longest = Math.max(longest, words.length);
      var tokens = Stream.concat(Arrays.stream(words), Stream.of(END)).toList();
      var previous = START;
      var beforePrevious = START;
      for (var token : tokens) {
        trigramCounts
            .computeIfAbsent(new Pair(beforePrevious, previous), k -> new LinkedHashMap<>())
            .merge(token, 1L, Long::sum);
        bigramCounts
            .computeIfAbsent(previous, k -> new LinkedHashMap<>())
            .merge(token, 1L, Long::sum);
        beforePrevious = previous;
        previous = token;
      }
    }
    return new NGramModel(trigramCounts, bigramCounts, longest);
  }

  /** Never longer than the longest training text. */
  String generate(RandomGenerator rng) {
    var words = new ArrayList<String>();
    var previous = START;
    var beforePrevious = START;
    while (words.size() < maxTokens) {
      var next = successors(beforePrevious, previous, rng).pick(rng);
      if (next.equals(END)) {
        break;
      }
      words.add(next);
      beforePrevious = previous;
      previous = next;
    }
    return String.join(" ", words);
  }

  private Weighted<String> successors(String beforePrevious, String previous, RandomGenerator rng) {
    var exact =
        rng.nextDouble() < BACKOFF ? null : trigrams.get(new Pair(beforePrevious, previous));
    return exact != null ? exact : bigrams.get(previous);
  }
}
