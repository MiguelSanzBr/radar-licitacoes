package br.dev.miguelsanzbr.radar.ingest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A {@code synchronized} method or block on the I/O path pins a virtual thread to its carrier while
 * it blocks (JEP 491 removed that for monitors on JDK 24+, but the rule keeps the path free of them
 * so the code stays correct on any runtime and lock waits stay visible to Resilience4j and JFR).
 * ArchUnit sees method modifiers but not blocks, so blocks are caught by a source scan.
 */
class IoPathHasNoSynchronizedTest {

  private static final String[] IO_PATH = {
    "br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp..",
    "br.dev.miguelsanzbr.radar.ingest.application.."
  };

  private static final List<Path> IO_SOURCES =
      List.of(
          Path.of("src/main/java/br/dev/miguelsanzbr/radar/ingest/adapter/out/pncp"),
          Path.of("src/main/java/br/dev/miguelsanzbr/radar/ingest/application"));

  private static final Pattern MONITOR = Pattern.compile("\\bsynchronized\\b");

  private static final ArchRule NO_SYNCHRONIZED_METHODS =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(IO_PATH)
          .should()
          .haveModifier(JavaModifier.SYNCHRONIZED)
          .because("a monitor on the I/O path can pin the virtual thread that blocks inside it");

  /** Whole lines of code that use the keyword; comments and string literals do not count. */
  static List<String> monitorLines(String source) {
    return source
        .lines()
        .map(IoPathHasNoSynchronizedTest::codeOf)
        .filter(line -> MONITOR.matcher(line).find())
        .toList();
  }

  private static String codeOf(String line) {
    var trimmed = line.strip();
    if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
      return "";
    }
    return line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("//.*", "");
  }

  @Test
  void noMethodOnTheIoPathIsSynchronized() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("br.dev.miguelsanzbr.radar.ingest");

    NO_SYNCHRONIZED_METHODS.check(classes);
  }

  @Test
  void noSourceFileOnTheIoPathHasASynchronizedBlockOrMethod() throws IOException {
    for (var root : IO_SOURCES) {
      assertThat(root).isDirectory();
      try (Stream<Path> files = Files.walk(root)) {
        for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          assertThat(monitorLines(Files.readString(file))).as(file.toString()).isEmpty();
        }
      }
    }
  }

  @Test
  void theMethodRuleSeesASynchronizedMethod() {
    JavaClasses fixture = new ClassFileImporter().importClasses(SynchronizedFixture.class);

    var result =
        noMethods()
            .that(DescribedPredicate.alwaysTrue())
            .should()
            .haveModifier(JavaModifier.SYNCHRONIZED)
            .evaluate(fixture);

    assertThat(result.hasViolation()).isTrue();
  }

  @Test
  void theSourceScanSeesBlocksAndMethodsButNotCommentsOrStrings() {
    var source =
        """
        class A {
          // synchronized in a comment
          /** synchronized in javadoc */
          String s = "synchronized in a string";
          synchronized void m() {}
          void n() { synchronized (this) {} }
        }
        """;

    assertThat(monitorLines(source))
        .containsExactly("  synchronized void m() {}", "  void n() { synchronized (this) {} }");
  }

  static final class SynchronizedFixture {
    synchronized void locked() {}
  }
}
