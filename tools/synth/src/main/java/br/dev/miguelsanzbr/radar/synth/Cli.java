package br.dev.miguelsanzbr.radar.synth;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/** Command line of the generator: {@code --mode=copy|events --count=N [--seed=S] ...}. */
enum Cli {
  ; // no constants: a holder of static methods that cannot be instantiated

  static final String DEFAULT_OUT = "data/synth-events.jsonl.gz";

  private static final String DEFAULT_CORPUS = "infra/compose/seed";
  private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/radar";
  private static final Pattern OPTION = Pattern.compile("^--([a-z-]+)=(.*)$");
  private static final Set<String> KNOWN =
      Set.of("mode", "count", "seed", "corpus", "out", "jdbc-url", "user", "password-env", "batch");
  private static final String USAGE =
      "uso: java -jar synth.jar --mode=copy|events --count=N [--seed=42]"
          + " [--corpus=ARQUIVO_OU_PASTA,...] (padrão infra/compose/seed)\n"
          + "  events: [--out=data/synth-events.jsonl.gz]\n"
          + "  copy: [--jdbc-url=jdbc:postgresql://localhost:5432/radar] [--user=radar]"
          + " [--password-env=RADAR_DB_PASSWORD] [--batch=50000]";

  /** Calls {@code exit} only for a non-zero code: a clean run just lets the JVM end. */
  static void exitOnFailure(
      String[] args, Map<String, String> env, PrintStream out, PrintStream err, IntConsumer exit) {
    int code = run(args, env, out, err);
    if (code != 0) {
      exit.accept(code);
    }
  }

  /** Returns the process exit code: 0 ok, 1 usage or failure. The password only comes from env. */
  static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
    try {
      generate(options(args), env, out);
      return 0;
    } catch (IllegalArgumentException e) {
      err.println("synth: " + e.getMessage());
      err.println(USAGE);
      return 1;
    } catch (IOException | SQLException | UncheckedIOException e) {
      err.println("synth: " + e.getMessage());
      return 1;
    }
  }

  private static Map<String, String> options(String[] args) {
    var options = new HashMap<String, String>();
    for (var arg : args) {
      var matcher = OPTION.matcher(arg);
      if (!matcher.matches() || !KNOWN.contains(matcher.group(1))) {
        throw new IllegalArgumentException("argumento inválido: " + arg);
      }
      options.put(matcher.group(1), matcher.group(2));
    }
    return options;
  }

  private static void generate(
      Map<String, String> options, Map<String, String> env, PrintStream out)
      throws IOException, SQLException {
    var mode = options.getOrDefault("mode", "");
    if (!Set.of("copy", "events").contains(mode)) {
      throw new IllegalArgumentException("--mode deve ser copy ou events");
    }
    long count = positive("count", options.getOrDefault("count", ""));
    long seed = parse("seed", options.getOrDefault("seed", "42"));
    int batch = (int) positive("batch", options.getOrDefault("batch", "50000"));
    var corpusPaths =
        Arrays.stream(options.getOrDefault("corpus", DEFAULT_CORPUS).split(","))
            .map(String::strip)
            .map(Path::of)
            .toList();
    var generator = new Generator(Corpus.load(corpusPaths), seed, count);
    long started = System.nanoTime();
    String result;
    if (mode.equals("events")) {
      var file = Path.of(options.getOrDefault("out", DEFAULT_OUT));
      EventsWriter.write(file, generator, count);
      result = "arquivo " + file;
    } else {
      result = "inseridas " + copy(options, env, generator, count, batch);
    }
    double seconds = Math.max(System.nanoTime() - started, 1) / 1e9;
    out.println(
        String.format(
            Locale.ROOT,
            "gerou %d linhas em %.1f s (%.0f linhas/s), seed %d, %s",
            count,
            seconds,
            count / seconds,
            seed,
            result));
  }

  private static long copy(
      Map<String, String> options,
      Map<String, String> env,
      Generator generator,
      long count,
      int batch)
      throws SQLException {
    var passwordVariable = options.getOrDefault("password-env", "RADAR_DB_PASSWORD");
    var password = env.get(passwordVariable);
    if (password == null) {
      throw new IllegalArgumentException("variável de ambiente " + passwordVariable + " ausente");
    }
    try (var connection =
        DriverManager.getConnection(
            options.getOrDefault("jdbc-url", DEFAULT_URL),
            options.getOrDefault("user", "radar"),
            password)) {
      return CopyLoader.load(connection, generator, count, batch);
    }
  }

  private static long positive(String name, String value) {
    long number = parse(name, value);
    if (number < 1) {
      throw new IllegalArgumentException("--" + name + " deve ser maior que zero");
    }
    return number;
  }

  private static long parse(String name, String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + name + " deve ser um número: " + value, e);
    }
  }
}
