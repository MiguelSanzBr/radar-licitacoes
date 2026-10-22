package br.dev.miguelsanzbr.radar.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class JsonlSinkTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ObjectNode node(int n) {
    return JSON.createObjectNode().put("n", n).put("t", "é");
  }

  @Test
  void writesOneJsonLinePerRecordAndFlushesEachOne(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.jsonl");
    JsonlSink sink = new JsonlSink(file);

    sink.accept(node(1));
    assertThat(Files.readAllLines(file)).containsExactly("{\"n\":1,\"t\":\"é\"}");
    sink.accept(node(2));

    assertThat(Files.readAllLines(file)).hasSize(2);
  }

  @Test
  void aFreshSinkOnAnExistingFileAppendsAndKeepsOldLines(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.jsonl");
    new JsonlSink(file).accept(node(1));

    new JsonlSink(file).accept(node(2));

    assertThat(Files.readAllLines(file))
        .containsExactly("{\"n\":1,\"t\":\"é\"}", "{\"n\":2,\"t\":\"é\"}");
  }

  @Test
  void anUnwritableTargetFailsLoudly(@TempDir Path dir) {
    JsonlSink sink = new JsonlSink(dir.resolve("missing-dir").resolve("r.jsonl"));

    assertThatThrownBy(() -> sink.accept(node(1))).isInstanceOf(UncheckedIOException.class);
  }
}
