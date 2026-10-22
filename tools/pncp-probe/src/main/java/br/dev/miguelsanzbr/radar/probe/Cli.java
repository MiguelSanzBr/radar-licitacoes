package br.dev.miguelsanzbr.radar.probe;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/** Command line: {@code run} the measurement schedule or build the {@code report}. */
enum Cli {
  ; // no constants: a holder of static methods that cannot be instantiated

  private static final String DEFAULT_OUT = "tools/pncp-probe/out";

  /** Calls {@code exit} only for a non-zero code: a clean run just lets the JVM end. */
  static void exitOnFailure(String[] args, PrintStream out, PrintStream err, IntConsumer exit)
      throws IOException {
    int code = run(args, out, err);
    if (code != 0) {
      exit.accept(code);
    }
  }

  /** Returns the process exit code: 0 ok, 1 usage, 2 stopped by HTTP 429 or 403. */
  static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
    Map<String, String> options =
        Arrays.stream(args)
            .skip(1)
            .filter(a -> a.startsWith("--") && a.contains("="))
            .map(a -> a.substring(2).split("=", 2))
            .collect(Collectors.toMap(p -> p[0], p -> p[1], (first, last) -> last));
    return switch (args.length == 0 ? "" : args[0]) {
      case "run" -> measure(options, out);
      case "report" -> report(options, out);
      default -> {
        err.println(
            "uso: pncp-probe run|report [--duration=PT24H] [--out=DIR] [--base-url=URL]"
                + " [--zone=ZONA] [--in=DIR] [--to=ARQUIVO.md]");
        yield 1;
      }
    };
  }

  private static int measure(Map<String, String> options, PrintStream out) throws IOException {
    ZoneId zone = ZoneId.of(options.getOrDefault("zone", "America/Sao_Paulo"));
    Duration duration = Duration.parse(options.getOrDefault("duration", "PT24H"));
    Path dir = Path.of(options.getOrDefault("out", DEFAULT_OUT));
    Files.createDirectories(dir);
    InstantSource clock = InstantSource.system();
    Path file =
        dir.resolve(
            "requests-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(clock.instant().atZone(zone))
                + ".jsonl");
    out.println("sonda iniciada: duração " + duration + ", saída " + file);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Prober prober =
          new Prober(
              new Prober.Config(
                  options.getOrDefault("base-url", "https://pncp.gov.br"), duration, zone),
              HttpTransport.standard(),
              clock,
              Sleeper.system(),
              new JsonlSink(file),
              executor,
              RandomGenerator.getDefault());
      int stop = prober.run();
      out.println(
          "sonda encerrada: %d requisições, parada: %s"
              .formatted(prober.requests(), stop == 0 ? "nenhuma" : "HTTP " + stop));
      return stop == 0 ? 0 : 2;
    }
  }

  private static int report(Map<String, String> options, PrintStream out) throws IOException {
    LocalDate today = LocalDate.now(ZoneId.of(options.getOrDefault("zone", "America/Sao_Paulo")));
    Path to = Path.of(options.getOrDefault("to", "docs/benchmarks/probe/" + today + ".md"));
    Report.generate(Path.of(options.getOrDefault("in", DEFAULT_OUT)), to, today);
    out.println("relatório gravado em " + to);
    return 0;
  }
}
