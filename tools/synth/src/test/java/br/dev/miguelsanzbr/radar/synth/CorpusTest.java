package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusTest {

  private static final java.util.random.RandomGenerator RNG =
      RandomGeneratorFactory.of("L64X128MixRandom").create(11L);

  @Test
  void learnsTheDistributionsOfTheRecordedFixture() throws IOException {
    var corpus = Corpus.load(List.of(Fixtures.SEED));
    var real = Fixtures.realRecords();
    var latest =
        real.stream()
            .map(r -> LocalDateTime.parse(r.get("dataPublicacaoPncp").asString()))
            .max(LocalDateTime::compareTo)
            .orElseThrow();

    assertThat(corpus.lastPublished()).isEqualTo(latest);
    // why: the fixture is the modality 6 (electronic auction) only
    assertThat(corpus.modalities().pick(RNG).id()).isEqualTo(6);
    assertThat(corpus.situations().pick(RNG).name()).isNotBlank();
    assertThat(corpus.places().pick(RNG).uf()).hasSize(2);
    assertThat(corpus.objects().generate(RNG)).isNotBlank();
    assertThat(corpus.organs().generate(RNG)).isNotBlank();
    assertThat(corpus.units().generate(RNG)).isNotBlank();
  }

  @Test
  void readsPagesAndBareRecordsFromAFileAndKeepsAbsentFieldsAsNull() {
    var corpus = Corpus.load(List.of(Fixtures.EDGE));

    assertThat(corpus.lastPublished()).isEqualTo(LocalDateTime.parse("2026-10-03T12:00:00"));
    var seen = new java.util.HashSet<Object>();
    for (int i = 0; i < 400; i++) {
      seen.add(corpus.valueBins().pick(RNG));
      seen.add(corpus.windows().pick(RNG));
      seen.add(corpus.disputeModes().pick(RNG));
      seen.add(corpus.legalBases().pick(RNG));
      seen.add(corpus.places().pick(RNG));
    }
    assertThat(seen).contains((Object) null);
    assertThat(corpus.srp().pick(RNG)).isInstanceOf(Boolean.class);
  }

  @Test
  void readsPlainAndCompressedFilesOfADirectory(@TempDir Path dir) throws IOException {
    Files.copy(Fixtures.EDGE, dir.resolve("edge.jsonl"));
    Files.copy(Files.list(Fixtures.SEED).findFirst().orElseThrow(), dir.resolve("seed.jsonl.gz"));
    Files.writeString(dir.resolve("ignored.txt"), "not json");

    var corpus = Corpus.load(List.of(dir));

    assertThat(corpus.objects().generate(RNG)).isNotBlank();
  }

  @Test
  void anEmptyCorpusIsRejected(@TempDir Path dir) throws IOException {
    var empty = Files.writeString(dir.resolve("empty.jsonl"), "\n");

    assertThatThrownBy(() -> Corpus.load(List.of(empty)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("corpus");
  }

  @Test
  void aCorpusOfRecordsWithoutTextsIsRejected(@TempDir Path dir) throws IOException {
    var bare =
        Files.writeString(
            dir.resolve("bare.jsonl"),
            "{\"modalidadeId\":6,\"modalidadeNome\":\"x\",\"situacaoCompraId\":1,\"dataPublicacaoPncp\":\"2026-10-01T08:00:00\"}\n");

    assertThatThrownBy(() -> Corpus.load(List.of(bare)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("corpus");
  }

  private static final String GOOD =
      "{\"objetoCompra\":\"a\",\"orgaoEntidade\":{\"razaoSocial\":\"b\"},"
          + "\"unidadeOrgao\":{\"nomeUnidade\":\"c\"},\"modalidadeId\":6,"
          + "\"modalidadeNome\":\"Pregão\",\"situacaoCompraId\":1,"
          + "\"dataPublicacaoPncp\":\"2026-10-01T08:00:00\"}";

  @Test
  void recordsWithoutModalityOrSituationAreSkipped(@TempDir Path dir) throws IOException {
    var file =
        Files.writeString(
            dir.resolve("mixed.jsonl"),
            String.join(
                "\n",
                GOOD.replace("\"modalidadeId\":6,", ""),
                GOOD.replace("\"modalidadeNome\":\"Pregão\",", ""),
                GOOD.replace("\"situacaoCompraId\":1,", ""),
                GOOD));

    var corpus = Corpus.load(List.of(file));

    assertThat(corpus.modalities().pick(RNG).name()).isEqualTo("Pregão");
    assertThat(corpus.situations().pick(RNG)).isNotNull();
  }

  @Test
  void aRecordWithoutPublicationDateIsRejected(@TempDir Path dir) throws IOException {
    var file =
        Files.writeString(dir.resolve("nodate.jsonl"), GOOD.replace("dataPublicacaoPncp", "x"));

    assertThatThrownBy(() -> Corpus.load(List.of(file)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dataPublicacaoPncp");
  }

  @Test
  void malformedJsonAndDatesNameTheFile(@TempDir Path dir) throws IOException {
    var broken = Files.writeString(dir.resolve("broken.jsonl"), "{not json");
    var badDate =
        Files.writeString(
            dir.resolve("bad-date.jsonl"), GOOD.replace("2026-10-01T08:00:00", "yesterday"));

    assertThatThrownBy(() -> Corpus.load(List.of(broken)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("corpus inválido em")
        .hasMessageContaining("broken.jsonl");
    assertThatThrownBy(() -> Corpus.load(List.of(badDate)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bad-date.jsonl");
  }

  @Test
  void aMissingPathIsAnError() {
    assertThatThrownBy(() -> Corpus.load(List.of(Path.of("does-not-exist"))))
        .isInstanceOf(java.io.UncheckedIOException.class);
  }
}
