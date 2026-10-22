package br.dev.miguelsanzbr.radar.synth;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres 18 per JVM, migrated once with the migrations of the core module. */
final class SharedPostgres {

  static final PostgreSQLContainer INSTANCE = new PostgreSQLContainer("postgres:18");

  static {
    INSTANCE.start();
    Flyway.configure()
        .dataSource(INSTANCE.getJdbcUrl(), INSTANCE.getUsername(), INSTANCE.getPassword())
        .locations("filesystem:../../core/src/main/resources/db/migration")
        .placeholders(Map.of("readWriterPassword", "unused"))
        .load()
        .migrate();
  }

  private SharedPostgres() {}
}
