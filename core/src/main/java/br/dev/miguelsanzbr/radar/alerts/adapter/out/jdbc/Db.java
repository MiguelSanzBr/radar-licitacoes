package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/** Instant to timestamptz conversions: the PostgreSQL driver has no direct Instant support. */
final class Db {

  private Db() {}

  static StatementSpec timestamp(StatementSpec sql, String name, Instant value) {
    return sql.param(
        name,
        value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC),
        Types.TIMESTAMP_WITH_TIMEZONE);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
