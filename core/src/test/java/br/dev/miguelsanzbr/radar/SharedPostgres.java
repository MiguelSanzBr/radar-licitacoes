package br.dev.miguelsanzbr.radar;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres 18 per JVM, shared by every test class that needs a database. */
public final class SharedPostgres {

  // why: wal_level=logical lets the slot gauge of the CDC mode be tested against a real slot; the
  // polling mode is indifferent to it
  // why: Spring caches every distinct test context with its own pools (10 connections each, now two
  // pools in the contexts that use the search side), so the default 100 connections run out
  public static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer("postgres:18")
          .withCommand("postgres", "-c", "max_connections=400", "-c", "wal_level=logical");

  static {
    INSTANCE.start();
  }

  private SharedPostgres() {}
}
