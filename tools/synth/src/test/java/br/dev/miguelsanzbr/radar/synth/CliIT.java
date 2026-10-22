package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliIT {

  @Test
  void copyModeLoadsTheDatabaseWithThePasswordFromTheEnvironment() throws Exception {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var db = SharedPostgres.INSTANCE;
    try (var c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var s = c.createStatement()) {
      s.execute("TRUNCATE search.procurement_search_view, search.procurement_detail_view");
    }

    int code =
        Cli.run(
            new String[] {
              "--mode=copy",
              "--count=1200",
              "--seed=42",
              "--jdbc-url=" + db.getJdbcUrl(),
              "--user=" + db.getUsername(),
              "--password-env=SYNTH_DB_PASSWORD",
              "--batch=500",
              "--corpus=" + Fixtures.SEED
            },
            Map.of("SYNTH_DB_PASSWORD", db.getPassword()),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("1200 linhas").contains("inseridas");
    try (var c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "SELECT count(*) FROM search.procurement_search_view WHERE source = 'synthetic'")) {
      rs.next();
      assertThat(rs.getLong(1)).isEqualTo(1_200);
    }
  }
}
