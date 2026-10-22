package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class EventsWriterTest {

  private static final String ID_PREFIX = "https://radar.miguelsanzbr.dev/schemas/";
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder
                  .schemaIdResolvers(ids -> ids.mapPrefix(ID_PREFIX, "classpath:"))
                  .schemaRegistryConfig(
                      SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static Corpus corpus;

  @BeforeAll
  static void load() {
    corpus = Corpus.load(List.of(Fixtures.SEED));
  }

  private static Schema schema(String name) {
    return REGISTRY.getSchema(SchemaLocation.of(ID_PREFIX + "events/" + name + ".schema.json"));
  }

  private static List<String> lines(Path file) throws IOException {
    try (var in = new GZIPInputStream(Files.newInputStream(file))) {
      return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
    }
  }

  private static Path write(Path dir, String name, long seed, int count) throws IOException {
    var file = dir.resolve(name);
    EventsWriter.write(file, new Generator(corpus, seed, count), count);
    return file;
  }

  @Test
  void sameSeedIsByteIdenticalAndAnotherSeedIsNot(@TempDir Path dir) throws IOException {
    var first = write(dir, "a.jsonl.gz", 42, 500);
    var again = write(dir, "b.jsonl.gz", 42, 500);
    var other = write(dir, "c.jsonl.gz", 43, 500);

    assertThat(Files.mismatch(first, again)).isEqualTo(-1);
    assertThat(Files.mismatch(first, other)).isNotEqualTo(-1);
  }

  @Test
  void aSampleOfOneThousandEnvelopesMatchesTheEventSchemas(@TempDir Path dir) throws IOException {
    var lines = lines(write(dir, "events.jsonl.gz", 42, 1_000));

    assertThat(lines).hasSize(1_000);
    var eventIds = new HashSet<String>();
    for (var line : lines) {
      var envelope = JSON.readTree(line);
      assertThat(envelope.get("aggregateId").asString()).startsWith("SYN-");
      assertThat(envelope.get("aggregateVersion").asInt()).isEqualTo(1);
      assertThat(eventIds.add(envelope.get("eventId").asString())).isTrue();
      assertThat(schema("envelope").validate(line, InputFormat.JSON)).isEmpty();
      assertThat(schema("procurement-discovered.v1").validate(line, InputFormat.JSON)).isEmpty();
    }
  }

  @Test
  void theEventIdIsAVersionSevenUuidOfTheEventTime(@TempDir Path dir) throws IOException {
    var first = JSON.readTree(lines(write(dir, "e.jsonl.gz", 1, 1)).getFirst());
    var id = first.get("eventId").asString();
    var occurredAt = java.time.Instant.parse(first.get("occurredAt").asString());

    assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    assertThat(java.util.UUID.fromString(id).getMostSignificantBits() >>> 16)
        .isEqualTo(occurredAt.toEpochMilli());
  }
}
