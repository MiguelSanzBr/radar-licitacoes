package br.dev.miguelsanzbr.radar.synth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the generator knows about real procurements, learned from recorded PNCP pages or records
 * (JSON lines, plain or gzip). Nothing learned is stored: it lives in memory for one run.
 */
record Corpus(
    NGramModel objects,
    NGramModel organs,
    NGramModel units,
    Weighted<Place> places,
    Weighted<IdName> modalities,
    Weighted<IdName> situations,
    Weighted<IdName> disputeModes,
    Weighted<IdName> legalBases,
    Weighted<Boolean> srp,
    Weighted<Integer> valueBins,
    Weighted<Window> windows,
    LocalDateTime lastPublished) {

  record Place(String municipality, String uf, String ibge) {}

  record IdName(int id, String name) {}

  /** Seconds from publication to the opening of proposals, and the length of the window. */
  record Window(long openingOffset, long length) {}

  /** Four value bins per decade: bin n holds 10^(n/4) up to 10^((n+1)/4). */
  static final int BINS_PER_DECADE = 4;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Reads files and directories (the {@code .jsonl} and {@code .jsonl.gz} inside them). */
  static Corpus load(List<Path> paths) {
    var learned = new Learned();
    try {
      for (var path : paths) {
        for (var file : files(path)) {
          read(file, learned);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return learned.build();
  }

  private static List<Path> files(Path path) throws IOException {
    if (!Files.isDirectory(path)) {
      return List.of(path);
    }
    try (var children = Files.list(path)) {
      return children
          .filter(f -> f.toString().endsWith(".jsonl") || f.toString().endsWith(".jsonl.gz"))
          .sorted()
          .toList();
    }
  }

  private static void read(Path file, Learned learned) throws IOException {
    try (var raw = Files.newInputStream(file);
        var in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      reader
          .lines()
          .filter(line -> !line.isBlank())
          .forEach(line -> learned.page(JSON.readTree(line)));
    } catch (JacksonException | DateTimeParseException e) {
      throw new IllegalArgumentException("corpus inválido em " + file + ": " + e.getMessage(), e);
    }
  }

  /** Accumulates counts record by record, so a corpus of any size is read in one pass. */
  private static final class Learned {
    private final List<String> objects = new ArrayList<>();
    private final List<String> organs = new ArrayList<>();
    private final List<String> units = new ArrayList<>();
    private final Map<Place, Long> places = new LinkedHashMap<>();
    private final Map<IdName, Long> modalities = new LinkedHashMap<>();
    private final Map<IdName, Long> situations = new LinkedHashMap<>();
    private final Map<IdName, Long> disputeModes = new LinkedHashMap<>();
    private final Map<IdName, Long> legalBases = new LinkedHashMap<>();
    private final Map<Boolean, Long> srp = new LinkedHashMap<>();
    private final Map<Integer, Long> valueBins = new LinkedHashMap<>();
    private final Map<Window, Long> windows = new LinkedHashMap<>();
    private LocalDateTime lastPublished = LocalDateTime.MIN;
    private long records;

    /** A line is a PNCP page ({@code data} array) or one bare record. */
    void page(JsonNode line) {
      var data = line.get("data");
      Iterable<JsonNode> found = data == null ? List.of(line) : data;
      found.forEach(this::record);
    }

    private void record(JsonNode r) {
      var modality = idName(r, "modalidadeId", "modalidadeNome");
      var situation = idName(r, "situacaoCompraId", "situacaoCompraNome");
      // why: a row needs a modality name (it weighs in the search vector) and a situation to be
      // built
      if (modality == null || modality.name() == null || situation == null) {
        return;
      }
      var published = time(r, "dataPublicacaoPncp");
      if (published == null) {
        throw new IllegalArgumentException("registro sem dataPublicacaoPncp");
      }
      records++;
      add(objects, r.path("objetoCompra").asString(null));
      add(organs, r.path("orgaoEntidade").path("razaoSocial").asString(null));
      var unit = r.path("unidadeOrgao");
      add(units, unit.path("nomeUnidade").asString(null));
      count(
          places,
          new Place(
              unit.path("municipioNome").asString(null),
              unit.path("ufSigla").asString(null),
              unit.path("codigoIbge").asString(null)));
      count(modalities, modality);
      count(situations, situation);
      count(disputeModes, idName(r, "modoDisputaId", "modoDisputaNome"));
      count(legalBases, idName(r.path("amparoLegal"), "codigo", "nome"));
      count(srp, r.path("srp").asBoolean(false));
      var value = r.path("valorTotalEstimado");
      count(
          valueBins,
          value.isNumber() && value.doubleValue() > 0
              ? (Integer) (int) Math.floor(Math.log10(value.doubleValue()) * BINS_PER_DECADE)
              : null);
      lastPublished = lastPublished.isAfter(published) ? lastPublished : published;
      var opening = time(r, "dataAberturaProposta");
      var closing = time(r, "dataEncerramentoProposta");
      count(
          windows,
          opening == null || closing == null
              ? null
              : new Window(
                  Duration.between(published, opening).toSeconds(),
                  Duration.between(opening, closing).toSeconds()));
    }

    Corpus build() {
      if (records == 0 || Stream.of(objects, organs, units).anyMatch(List::isEmpty)) {
        throw new IllegalArgumentException("corpus sem textos: nada para aprender");
      }
      return new Corpus(
          NGramModel.train(objects),
          NGramModel.train(organs),
          NGramModel.train(units),
          new Weighted<>(places),
          new Weighted<>(modalities),
          new Weighted<>(situations),
          new Weighted<>(disputeModes),
          new Weighted<>(legalBases),
          new Weighted<>(srp),
          new Weighted<>(valueBins),
          new Weighted<>(windows),
          lastPublished);
    }

    private static void add(List<String> texts, String text) {
      if (text != null) {
        texts.add(text);
      }
    }

    private static <T> void count(Map<T, Long> counts, T key) {
      counts.merge(key, 1L, Long::sum);
    }

    private static IdName idName(JsonNode node, String idField, String nameField) {
      var id = node.path(idField);
      return id.isNumber() ? new IdName(id.intValue(), node.path(nameField).asString(null)) : null;
    }

    private static LocalDateTime time(JsonNode node, String field) {
      var text = node.path(field).asString(null);
      return text == null ? null : LocalDateTime.parse(text);
    }
  }
}
