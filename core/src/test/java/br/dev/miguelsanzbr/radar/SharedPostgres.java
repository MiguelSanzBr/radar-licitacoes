package br.dev.miguelsanzbr.radar;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres 18 per JVM, shared by every test class that needs a database. */
public final class SharedPostgres {

  public static final PostgreSQLContainer INSTANCE = new PostgreSQLContainer("postgres:18");

  static {
    INSTANCE.start();
  }

  private SharedPostgres() {}
}
