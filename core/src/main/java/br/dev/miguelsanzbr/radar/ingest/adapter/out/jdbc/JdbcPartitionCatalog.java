package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.ingest.application.port.PartitionCatalog;
import java.time.YearMonth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC over {@code ingest.ensure_partition} (migration V2), serialized by an advisory lock,
 * and {@code to_regclass}.
 */
@Component
class JdbcPartitionCatalog implements PartitionCatalog {

  private final JdbcTemplate jdbc;

  JdbcPartitionCatalog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void ensure(YearMonth month) {
    // One statement, so the transaction lock lasts until the DDL commits: replicas running the
    // job together would otherwise race on CREATE TABLE IF NOT EXISTS.
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext('ingest.ensure_partition')),"
            + " ingest.ensure_partition(?)",
        (rs, row) -> rs.getString(2),
        month.atDay(1));
  }

  @Override
  public boolean exists(YearMonth month) {
    var name = "ingest.procurement_y%04dm%02d".formatted(month.getYear(), month.getMonthValue());
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
  }
}
