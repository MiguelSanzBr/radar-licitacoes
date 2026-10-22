package br.dev.miguelsanzbr.radar.synth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The recorded real fixture and the hand-written edge corpus, read without the code under test. */
final class Fixtures {

  static final Path SEED = Path.of("../../infra/compose/seed");
  static final Path EDGE = Path.of("src/test/resources/corpus-edge.jsonl");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Fixtures() {}

  /** Every procurement of the seed pages, as recorded. */
  static List<JsonNode> realRecords() {
    var records = new ArrayList<JsonNode>();
    try (Stream<Path> files = Files.list(SEED)) {
      for (var file : files.filter(f -> f.toString().endsWith(".jsonl.gz")).sorted().toList()) {
        try (var in = new GZIPInputStream(Files.newInputStream(file))) {
          for (var line : new String(in.readAllBytes(), "UTF-8").split("\n")) {
            JSON.readTree(line).get("data").forEach(records::add);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return records;
  }
}
