package br.dev.miguelsanzbr.radar.probe;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends one JSON line per record. Each line is a single append, so readers see whole lines, and a
 * new run on an existing file only adds to it.
 */
final class JsonlSink implements Consumer<ObjectNode> {

  private final Path file;

  JsonlSink(Path file) {
    this.file = file;
  }

  @Override
  public synchronized void accept(ObjectNode record) {
    try {
      Files.writeString(file, record + "\n", StandardCharsets.UTF_8, CREATE, APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
