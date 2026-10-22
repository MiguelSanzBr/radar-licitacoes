package br.dev.miguelsanzbr.radar.synth;

import br.dev.miguelsanzbr.radar.synth.Corpus.Place;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Deterministic stream of synthetic procurements: the same corpus, seed and count give the same
 * rows. Publication dates rise evenly over the 365 days before the latest date of the corpus, so
 * the output is in publication order (the order a replay wants) and the "last 12 months" do not
 * depend on the clock of the run.
 *
 * <p>Ids are {@code SYN-<cnpj>-1-<sequence>/<year>}: a fixed pool of synthetic organs, each with
 * its own sequence, so no two rows share an id and none can equal a PNCP control number.
 */
final class Generator {

  static final int ORGANS = 2_000;

  private record Organ(String cnpj, String name, String unitCode, String unitName, Place place) {}

  private final Corpus corpus;
  private final RandomGenerator rng;
  private final long count;
  private final long startSecond;
  private final long spanSeconds = Duration.ofDays(365).toSeconds();
  private final List<Organ> organs;
  private final int[] sequence = new int[ORGANS];
  private long index;

  Generator(Corpus corpus, long seed, long count) {
    this.corpus = corpus;
    this.rng = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    this.count = count;
    this.startSecond = corpus.lastPublished().toEpochSecond(ZoneOffset.UTC) - spanSeconds;
    var cnpjs = new LinkedHashSet<String>();
    while (cnpjs.size() < ORGANS) {
      cnpjs.add(String.format(Locale.ROOT, "%014d", rng.nextLong(100_000_000_000_000L)));
    }
    this.organs =
        cnpjs.stream()
            .map(
                cnpj ->
                    new Organ(
                        cnpj,
                        corpus.organs().generate(rng),
                        Integer.toString(1 + rng.nextInt(999_999)),
                        corpus.units().generate(rng),
                        corpus.places().pick(rng)))
            .toList();
  }

  Synthetic next() {
    var published =
        Instant.ofEpochSecond(
            startSecond + (long) (spanSeconds * ((index++ + rng.nextDouble()) / count)));
    int slot = rng.nextInt(ORGANS);
    var organ = organs.get(slot);
    var id =
        String.format(
            Locale.ROOT,
            "SYN-%s-1-%06d/%d",
            organ.cnpj(),
            ++sequence[slot],
            published.atZone(ZoneOffset.UTC).getYear());
    var situation = corpus.situations().pick(rng);
    var window = corpus.windows().pick(rng);
    Instant opening = null;
    Instant closing = null;
    if (window != null) {
      opening = published.plusSeconds(window.openingOffset());
      closing = opening.plusSeconds(window.length());
    }
    return new Synthetic(
        id,
        corpus.objects().generate(rng),
        organ.cnpj(),
        organ.name(),
        organ.unitCode(),
        organ.unitName(),
        organ.place().uf(),
        organ.place().municipality(),
        organ.place().ibge(),
        corpus.modalities().pick(rng),
        situation,
        Synthetic.statusOf(situation.id()),
        corpus.disputeModes().pick(rng),
        corpus.legalBases().pick(rng),
        corpus.srp().pick(rng),
        value(),
        opening,
        closing,
        published,
        published.plusSeconds(rng.nextLong(86_400)));
  }

  private BigDecimal value() {
    var bin = corpus.valueBins().pick(rng);
    return bin == null
        ? null
        : BigDecimal.valueOf(Math.pow(10, (bin + rng.nextDouble()) / Corpus.BINS_PER_DECADE))
            .setScale(2, RoundingMode.HALF_UP);
  }
}
