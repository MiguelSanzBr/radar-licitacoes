package br.dev.miguelsanzbr.radar.architecture.fixture.application;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Violates the application allow-list: JDBC is an adapter concern. */
public class UsesAForbiddenFramework {

  JdbcClient jdbc;
}
