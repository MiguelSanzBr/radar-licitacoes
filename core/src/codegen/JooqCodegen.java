import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.GeneratedAnnotationType;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Build step, run as a single-file program by exec-maven-plugin in generate-sources: starts a
 * throwaway Postgres, applies the real Flyway migrations and lets the jOOQ generator read the
 * {@code search} schema. Skipped, without touching Docker, while the output is newer than every
 * migration and than this file.
 *
 * <p>Arguments: migrations directory, generated sources directory, stamp file, package name, this
 * file.
 */
public final class JooqCodegen {

  public static void main(String[] args) throws Exception {
    var migrations = Path.of(args[0]);
    var output = Path.of(args[1]);
    var stamp = Path.of(args[2]);
    var packageName = args[3];
    var self = Path.of(args[4]);

    if (upToDate(stamp, output, migrations, self)) {
      System.out.println("jOOQ codegen: output is newer than the migrations, skipped");
      return;
    }
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);

    try (var postgres = new PostgreSQLContainer("postgres:18")) {
      postgres.start();
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .locations("filesystem:" + migrations.toAbsolutePath())
          .placeholders(Map.of("readWriterPassword", "codegen"))
          .load()
          .migrate();

      GenerationTool.generate(
          new Configuration()
              .withJdbc(
                  new Jdbc()
                      .withDriver("org.postgresql.Driver")
                      .withUrl(postgres.getJdbcUrl())
                      .withUser(postgres.getUsername())
                      .withPassword(postgres.getPassword()))
              .withGenerator(
                  new Generator()
                      .withDatabase(
                          new Database()
                              .withName("org.jooq.meta.postgres.PostgresDatabase")
                              .withInputSchema("search"))
                      .withGenerate(
                          new Generate()
                              .withGeneratedAnnotation(true)
                              .withGeneratedAnnotationType(GeneratedAnnotationType.ORG_JOOQ_GENERATED)
                              .withGeneratedAnnotationDate(false)
                              .withGeneratedAnnotationJooqVersion(false)
                              .withDeprecated(false)
                              .withComments(false))
                      .withTarget(
                          new Target()
                              .withPackageName(packageName)
                              .withDirectory(output.toString())
                              .withClean(true))));
    }
    Files.createDirectories(stamp.getParent());
    Files.writeString(stamp, "generated\n");
  }

  private static boolean upToDate(Path stamp, Path output, Path migrations, Path self)
      throws IOException {
    if (!Files.exists(stamp) || !Files.isDirectory(output)) {
      return false;
    }
    var built = Files.getLastModifiedTime(stamp);
    try (Stream<Path> inputs = Stream.concat(Files.list(migrations), Stream.of(self))) {
      return inputs.allMatch(
          input -> {
            try {
              return Files.getLastModifiedTime(input).compareTo(built) <= 0;
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    }
  }

  private JooqCodegen() {}
}
