package br.dev.miguelsanzbr.radar.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Compares the generated module documentation with the committed one. Modulith writes the relation
 * lines of a file in an unstable order, so each file is compared as its sorted list of lines; the
 * set of file names is compared too, so a module added or removed shows up.
 */
final class ModuleDocs {

  private ModuleDocs() {}

  /** One message per difference between the two folders; empty when they match. */
  static List<String> drift(Path committed, Path fresh) {
    var before = read(committed);
    var after = read(fresh);
    var problems = new ArrayList<String>();
    after.forEach(
        (name, lines) -> {
          if (!before.containsKey(name)) {
            problems.add("missing in docs/modules: " + name);
          } else if (!before.get(name).equals(lines)) {
            problems.add("content differs: " + name);
          }
        });
    before.keySet().stream()
        .filter(name -> !after.containsKey(name))
        .forEach(name -> problems.add("stale in docs/modules: " + name));
    return problems;
  }

  /**
   * Makes {@code committed} an exact copy of {@code fresh}, deleting files that no longer exist.
   */
  static void update(Path committed, Path fresh) {
    try {
      Files.createDirectories(committed);
      for (var name : read(committed).keySet()) {
        if (!Files.exists(fresh.resolve(name))) {
          Files.delete(committed.resolve(name));
        }
      }
      for (var name : read(fresh).keySet()) {
        Files.copy(
            fresh.resolve(name), committed.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, List<String>> read(Path dir) {
    var files = new TreeMap<String, List<String>>();
    if (!Files.isDirectory(dir)) {
      return files;
    }
    try (Stream<Path> list = Files.list(dir)) {
      for (var file : list.filter(Files::isRegularFile).toList()) {
        var lines = new ArrayList<>(Files.readAllLines(file));
        Collections.sort(lines);
        files.put(file.getFileName().toString(), lines);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return files;
  }
}
