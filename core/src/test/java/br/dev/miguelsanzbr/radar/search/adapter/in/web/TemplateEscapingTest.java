package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Thymeleaf escapes {@code th:text} and {@code [[...]]}; only {@code th:utext} and {@code [(...)]}
 * print markup as it is. No template may use them (OWASP A03).
 */
class TemplateEscapingTest {

  private static final Pattern UNESCAPED = Pattern.compile("th:utext|\\[\\(|data-th-utext");

  static boolean unescapes(String template) {
    return UNESCAPED.matcher(template).find();
  }

  @Test
  void noTemplateOutputsUnescapedText() throws IOException {
    var root = Path.of("src", "main", "resources", "templates");
    try (Stream<Path> files = Files.walk(root)) {
      var templates = files.filter(f -> f.toString().endsWith(".html")).toList();
      assertThat(templates).hasSizeGreaterThan(5);
      for (var template : templates) {
        assertThat(unescapes(Files.readString(template))).as(template.toString()).isFalse();
      }
    }
  }

  @Test
  void theScanSeesBothWaysToPrintMarkup() {
    assertThat(unescapes("<p th:utext=\"${x}\"></p>")).isTrue();
    assertThat(unescapes("<p>[(${x})]</p>")).isTrue();
    assertThat(unescapes("<p th:text=\"${x}\">[[${y}]]</p>")).isFalse();
  }
}
