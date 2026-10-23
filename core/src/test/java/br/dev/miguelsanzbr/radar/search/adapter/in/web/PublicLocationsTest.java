package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spring MVC serves {@code static/} and also the folders the framework and the servlet container
 * look in by default ({@code META-INF/resources}, {@code resources}, {@code public}); the
 * container's own root cannot be switched off by a property. So the guard is the content: nothing
 * but {@code static/} may exist among the resources of the application (OWASP A05).
 */
class PublicLocationsTest {

  private static final Path RESOURCES = Path.of("src", "main", "resources");

  @Test
  void onlyTheStaticFolderIsAPublicLocation() {
    assertThat(RESOURCES.resolve("static")).isDirectory();
    for (var folder : List.of("META-INF/resources", "resources", "public")) {
      assertThat(Files.exists(RESOURCES.resolve(folder))).as(folder).isFalse();
    }
  }
}
