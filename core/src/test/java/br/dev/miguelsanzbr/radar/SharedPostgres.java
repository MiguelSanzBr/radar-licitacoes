package br.dev.miguelsanzbr.radar;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres 18 per JVM, shared by every test class that needs a database. */
public final class SharedPostgres {

  // why: Spring caches every distinct test context with its own pools (10 connections each, now two
  // pools in the contexts that use the search side), so the default 100 connections run out
  public static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer("postgres:18").withCommand("postgres", "-c", "max_connections=400");

  static {
    INSTANCE.start();
  }

  private SharedPostgres() {}
}
