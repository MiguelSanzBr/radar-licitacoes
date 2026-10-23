package br.dev.miguelsanzbr.radar.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.RadarApplication;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.util.FileSystemUtils;

/** Module boundaries verified by Spring Modulith, and the module documentation kept in sync. */
class ModularityTest {

  private static final Path BASEDIR =
      Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).toAbsolutePath();

  private static final Path DOCS = BASEDIR.resolveSibling("docs").resolve("modules");

  private static final String UPDATE_COMMAND =
      "./mvnw test -pl core -am -Dtest=ModularityTest -Dsurefire.failIfNoSpecifiedTests=false"
          + " -Dmodules.docs.update=true";

  private static final ApplicationModules MODULES = ApplicationModules.of(RadarApplication.class);

  @Test
  void moduleBoundariesHold() {
    MODULES.verify();
  }

  @Test
  void everyTopLevelPackageIsAModule() {
    assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
        .contains(
            "alerts", "api", "identity", "ingest", "observability", "outbox", "search", "shared");
  }

  @Test
  void theSharedKernelDependsOnNoOtherModule() {
    var shared = MODULES.getModuleByName("shared").orElseThrow();

    assertThat(shared.getDirectDependencies(MODULES).stream()).isEmpty();
  }

  @Test
  void theSharedKernelIsOpenSoEveryModuleMayUseIt() {
    assertThat(MODULES.getModuleByName("shared").orElseThrow().isOpen()).isTrue();
  }

  @Test
  void outboxDependsOnNoContext() {
    var outbox = MODULES.getModuleByName("outbox").orElseThrow();

    assertThat(
            outbox.getDirectDependencies(MODULES).stream()
                .map(dependency -> dependency.getTargetModule().getIdentifier().toString()))
        .doesNotContain("ingest", "search", "alerts");
  }

  /** Generated under target/, never in the source tree; see UPDATE_COMMAND to refresh the docs. */
  @Test
  void committedModuleDocumentationMatchesTheCode() throws IOException {
    var fresh = BASEDIR.resolve("target").resolve("modules-docs");
    // a module removed since the last run must not survive in a non-clean target/
    FileSystemUtils.deleteRecursively(fresh);
    new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder(fresh.toString()))
        .writeDocumentation();

    var drift = ModuleDocs.drift(DOCS, fresh);
    if (!drift.isEmpty() && Boolean.getBoolean("modules.docs.update")) {
      ModuleDocs.update(DOCS, fresh);
      return;
    }

    assertThat(drift)
        .as("docs/modules is out of date, refresh and commit it with: %s", UPDATE_COMMAND)
        .isEmpty();
  }

  @Test
  void alertsDependsOnlyOnTheSharedKernelTheApiContractTheOutboxAndTracing() {
    var alerts = MODULES.getModuleByName("alerts").orElseThrow();

    var targets =
        alerts.getDirectDependencies(MODULES).stream()
            .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
            .distinct()
            .toList();

    assertThat(targets).containsExactlyInAnyOrder("shared", "api", "outbox", "observability");
  }

  @Test
  void identityDependsOnlyOnTheSharedKernelAndTheApiContract() {
    var identity = MODULES.getModuleByName("identity").orElseThrow();

    var targets =
        identity.getDirectDependencies(MODULES).stream()
            .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
            .distinct()
            .toList();

    assertThat(targets).containsExactlyInAnyOrder("shared", "api");
  }

  @Test
  void searchNeverReachesIntoTheWriteSide() {
    var search = MODULES.getModuleByName("search").orElseThrow();

    var targets =
        search.getDirectDependencies(MODULES).stream()
            .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
            .distinct()
            .toList();

    assertThat(targets).doesNotContain("ingest", "outbox", "alerts");
  }
}
