package br.dev.miguelsanzbr.radar.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests may not silence the compiler or assert nothing. {@code @SuppressWarnings} has SOURCE
 * retention, so ArchUnit cannot see it: this scans the test sources of every module instead.
 */
class SourceHygieneTest {

  private static final Pattern FORBIDDEN =
      Pattern.compile("@SuppressWarnings|assertTrue\\(\\s*true\\s*\\)");

  private static final String SELF =
      "core/src/test/java/br/dev/miguelsanzbr/radar/architecture/SourceHygieneTest.java";

  static List<String> offences(String source) {
    return source
        .lines()
        .filter(line -> FORBIDDEN.matcher(line).find())
        .map(String::strip)
        .toList();
  }

  private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*(\\S+)");
  private static final Pattern PINNED = Pattern.compile("[^@\\s]+@[0-9a-f]{40}");

  /** The {@code uses:} lines that name an action by anything but a full commit SHA. */
  static List<String> unpinnedActions(String workflow) {
    return workflow
        .lines()
        .map(USES::matcher)
        .filter(
            m -> m.find() && !m.group(1).startsWith("./") && !PINNED.matcher(m.group(1)).matches())
        .map(m -> m.group(1))
        .toList();
  }

  /** The reactor root: the folder above the module Maven runs the tests in. */
  private static Path reactorRoot() {
    return Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
        .toAbsolutePath()
        .getParent();
  }

  /** {@code src/test/java} of every module under the root that has one. */
  static List<Path> testSourceRoots(Path reactorRoot) throws IOException {
    var roots = new ArrayList<Path>();
    var modules = new ArrayList<Path>();
    try (Stream<Path> top = Files.list(reactorRoot)) {
      top.filter(Files::isDirectory).forEach(modules::add);
    }
    for (var module : List.copyOf(modules)) {
      try (Stream<Path> nested = Files.list(module)) {
        nested.filter(Files::isDirectory).forEach(modules::add);
      }
    }
    for (var module : modules) {
      var sources = module.resolve("src/test/java");
      if (Files.isDirectory(sources)) {
        roots.add(sources);
      }
    }
    return roots;
  }

  @Test
  void noTestSuppressesWarningsOrAssertsTrueLiterally() throws IOException {
    var root = reactorRoot();
    var self = root.resolve(SELF).normalize();
    var roots = testSourceRoots(root);
    assertThat(roots).contains(root.resolve("core/src/test/java"));
    for (var sources : roots) {
      try (Stream<Path> files = Files.walk(sources)) {
        for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          if (!file.normalize().equals(self)) {
            assertThat(offences(Files.readString(file))).as(file.toString()).isEmpty();
          }
        }
      }
    }
  }

  @Test
  void theScanSeesBothForbiddenPatterns() {
    var source =
        """
        @SuppressWarnings("unchecked")
        void a() { assertTrue(true); }
        void b() { assertTrue( true ); }
        void c() { assertTrue(isOk()); }
        """;

    assertThat(offences(source))
        .containsExactly(
            "@SuppressWarnings(\"unchecked\")",
            "void a() { assertTrue(true); }",
            "void b() { assertTrue( true ); }");
  }

  @Test
  void everyModuleWithTestSourcesIsScannedAtOneOrTwoLevelsDeep(@TempDir Path root)
      throws IOException {
    for (var dir : List.of("core", "e2e", "tools/probe", "tools/synth", "docs", "infra/none")) {
      Files.createDirectories(root.resolve(dir));
    }
    for (var dir : List.of("core", "e2e", "tools/probe", "tools/synth")) {
      Files.createDirectories(root.resolve(dir).resolve("src/test/java"));
    }

    assertThat(testSourceRoots(root))
        .containsExactlyInAnyOrder(
            root.resolve("core/src/test/java"),
            root.resolve("e2e/src/test/java"),
            root.resolve("tools/probe/src/test/java"),
            root.resolve("tools/synth/src/test/java"));
  }

  @Test
  void everyWorkflowActionIsPinnedByCommitSha() throws IOException {
    var workflows = reactorRoot().resolve(".github/workflows");
    try (Stream<Path> files = Files.list(workflows)) {
      var yaml = files.filter(f -> f.toString().endsWith(".yml")).toList();
      assertThat(yaml).isNotEmpty();
      for (var file : yaml) {
        assertThat(unpinnedActions(Files.readString(file))).as(file.toString()).isEmpty();
      }
    }
  }

  @Test
  void theWorkflowScanSeesTagsAndBranchesButNotShasOrLocalActions() {
    var workflow =
        """
        steps:
          - uses: actions/checkout@v4
          - uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 # v4.7.1
            uses: docker/build-push-action@main
          - uses: ./local-action
          - run: echo uses: not-an-action
        """;

    assertThat(unpinnedActions(workflow))
        .containsExactly("actions/checkout@v4", "docker/build-push-action@main");
  }
}
