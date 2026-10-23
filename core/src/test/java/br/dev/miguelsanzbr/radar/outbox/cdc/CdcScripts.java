package br.dev.miguelsanzbr.radar.outbox.cdc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs the opt-in scripts of {@code infra/cdc}, the way the runbook and the compose profile do. */
public final class CdcScripts {

  private static final Path CDC = Path.of("..", "infra", "cdc");

  private CdcScripts() {}

  public static void enable(JdbcTemplate jdbc) {
    jdbc.execute(read("enable-cdc.sql"));
  }

  public static void disable(JdbcTemplate jdbc) {
    jdbc.execute(read("disable-cdc.sql"));
  }

  /** For callers without a JdbcTemplate (a class-level cleanup). */
  public static void disable(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(read("disable-cdc.sql"));
    }
  }

  private static String read(String script) {
    try {
      return Files.readString(CDC.resolve(script));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
