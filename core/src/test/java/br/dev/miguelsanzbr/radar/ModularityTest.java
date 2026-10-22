package br.dev.miguelsanzbr.radar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

  private static final ApplicationModules MODULES = ApplicationModules.of(RadarApplication.class);

  @Test
  void moduleBoundariesHold() {
    MODULES.verify();
  }

  @Test
  void alertsDependsOnlyOnTheSharedKernelAndTheApiContract() {
    var alerts = MODULES.getModuleByName("alerts").orElseThrow();

    var targets =
        alerts.getDirectDependencies(MODULES).stream()
            .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
            .distinct()
            .toList();

    assertThat(targets).containsExactlyInAnyOrder("shared", "api");
  }

  @Test
  void identityDependsOnNoOtherModule() {
    var identity = MODULES.getModuleByName("identity").orElseThrow();

    assertThat(identity.getDirectDependencies(MODULES).stream()).isEmpty();
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
