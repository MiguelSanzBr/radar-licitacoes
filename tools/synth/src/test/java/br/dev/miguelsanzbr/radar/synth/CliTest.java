package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

  private static final String SEED = "--corpus=" + Fixtures.SEED;

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

  private int run(Map<String, String> env, String... args) {
    return Cli.run(
        args,
        env,
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void eventsModeWritesTheFileAndPrintsTheThroughput(@TempDir Path dir) throws IOException {
    var file = dir.resolve("events.jsonl.gz");

    int code = run(Map.of(), "--mode=events", "--count=250", "--seed=7", "--out=" + file, SEED);

    assertThat(code).isZero();
    assertThat(file).isNotEmptyFile();
    assertThat(out()).contains("250 linhas").contains("linhas/s").contains("seed 7");
    assertThat(err()).isEmpty();
  }

  @Test
  void eventsModeUsesTheDefaultsForSeedAndFile(@TempDir Path dir) throws IOException {
    var corpus = dir.resolve("corpus.jsonl");
    Files.copy(Fixtures.EDGE, corpus);

    int code =
        run(
            Map.of(),
            "--mode=events",
            "--count=3",
            "--out=" + dir.resolve("e.gz"),
            "--corpus=" + corpus + ", " + corpus);

    assertThat(code).isZero();
    assertThat(out()).contains("seed 42");
  }

  @Test
  void theDefaultOutputFileIsUnderTheIgnoredDataDirectory() {
    assertThat(Cli.DEFAULT_OUT).startsWith("data/");
  }

  @Test
  void usageErrorsExitWithOne() {
    List<String[]> bad = new ArrayList<>();
    bad.add(new String[] {});
    bad.add(new String[] {"--mode=events"});
    bad.add(new String[] {"--count=5"});
    bad.add(new String[] {"--mode=zip", "--count=5"});
    bad.add(new String[] {"--mode=events", "--count=0"});
    bad.add(new String[] {"--mode=events", "--count=x"});
    bad.add(new String[] {"--mode=events", "--count=5", "--seed=x"});
    bad.add(new String[] {"--mode=events", "--count=5", "stray"});
    bad.add(new String[] {"--mode=events", "--count=5", "--unknown=1"});
    for (var args : bad) {
      assertThat(run(Map.of(), args)).as(String.join(" ", args)).isEqualTo(1);
    }
    assertThat(err()).contains("uso:");
  }

  @Test
  void copyModeNeedsThePasswordInTheEnvironment() {
    int code = run(Map.of(), "--mode=copy", "--count=5", "--password-env=SYNTH_PW", SEED);

    assertThat(code).isEqualTo(1);
    assertThat(err()).contains("SYNTH_PW");
  }

  @Test
  void aMissingCorpusIsReportedNotThrown() {
    int code = run(Map.of(), "--mode=events", "--count=5", "--corpus=does-not-exist");

    assertThat(code).isEqualTo(1);
    assertThat(err()).contains("synth:");
  }

  @Test
  void aBrokenCorpusIsReportedWithItsFile(@TempDir Path dir) throws IOException {
    var broken = Files.writeString(dir.resolve("broken.jsonl"), "{not json");

    int code = run(Map.of(), "--mode=events", "--count=5", "--corpus=" + broken);

    assertThat(code).isEqualTo(1);
    assertThat(err()).contains("corpus inválido em").contains("broken.jsonl");
  }

  @Test
  void anUnreachableDatabaseIsReportedNotThrown() {
    int code =
        run(
            Map.of("RADAR_DB_PASSWORD", "x"),
            "--mode=copy",
            "--count=5",
            "--jdbc-url=jdbc:postgresql://127.0.0.1:1/radar",
            SEED);

    assertThat(code).isEqualTo(1);
    assertThat(err()).contains("synth:");
  }

  @Test
  void exitsOnlyOnFailure() {
    var codes = new ArrayList<Integer>();
    Cli.exitOnFailure(
        new String[] {},
        Map.of(),
        new PrintStream(outBytes),
        new PrintStream(errBytes),
        codes::add);
    assertThat(codes).containsExactly(1);

    codes.clear();
    Cli.exitOnFailure(
        new String[] {
          "--mode=events",
          "--count=2",
          "--out=" + System.getProperty("java.io.tmpdir") + "/synth-cli-test.jsonl.gz",
          SEED
        },
        Map.of(),
        new PrintStream(outBytes),
        new PrintStream(errBytes),
        codes::add);
    assertThat(codes).isEmpty();
  }

  @Test
  void theMainMethodRunsTheCliOnTheProcessEnvironment() {
    SynthMain.main(
        new String[] {
          "--mode=events",
          "--count=2",
          "--out=" + System.getProperty("java.io.tmpdir") + "/synth-main-test.jsonl.gz",
          SEED
        });
  }
}
