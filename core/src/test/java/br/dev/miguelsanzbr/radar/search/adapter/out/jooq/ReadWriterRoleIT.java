package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.SharedPostgres;
import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@OutboxSpringBootTest
class ReadWriterRoleIT {

  private static final String ROLE = "radar_read_writer";

  @Autowired JdbcTemplate jdbc;

  @Value("${spring.flyway.placeholders.readWriterPassword}")
  String password;

  @AfterEach
  void clean() {
    jdbc.update(
        "DELETE FROM search.procurement_search_view WHERE numero_controle_pncp LIKE 'IT-%'");
  }

  private Connection asReadWriter() throws SQLException {
    return DriverManager.getConnection(SharedPostgres.INSTANCE.getJdbcUrl(), ROLE, password);
  }

  @Test
  void cannotReadTheWriteSideTables() throws Exception {
    try (var connection = asReadWriter();
        var statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeQuery("SELECT 1 FROM ingest.procurement"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ingest", "outbox", "alerts", "identity"})
  void hasNoUsageOnTheOtherSchemas(String schema) {
    assertThat(
            jdbc.queryForObject(
                "SELECT has_schema_privilege(?, ?, 'USAGE')", Boolean.class, ROLE, schema))
        .isFalse();
  }

  @Test
  void writesTheSearchSchema() throws Exception {
    try (var connection = asReadWriter();
        var statement = connection.createStatement()) {
      var inserted =
          statement.executeUpdate(
              """
              INSERT INTO search.procurement_search_view
                (numero_controle_pncp, search_vector, object_description, organ_name, modality_id,
                 status, value_band, published_at, pncp_updated_at, source_version, projected_at)
              VALUES ('IT-1', to_tsvector('search.portuguese_unaccent', 'Licitação'), 'Licitação',
                      'Prefeitura', 6, 'PUBLISHED', 0, now(), now(), 1, now())
              """);
      assertThat(inserted).isEqualTo(1);
      assertThat(
              statement.executeUpdate(
                  "DELETE FROM search.procurement_search_view WHERE numero_controle_pncp = 'IT-1'"))
          .isEqualTo(1);
    }
  }

  @Test
  void canTruncateTheSearchTablesForAReplay() throws Exception {
    try (var connection = asReadWriter();
        var statement = connection.createStatement()) {
      statement.execute("TRUNCATE search.processed_event");
    }
  }

  @Test
  void theSearchConfigurationIgnoresAccentsAndCase() {
    assertThat(
            jdbc.queryForObject(
                """
                SELECT to_tsvector('search.portuguese_unaccent', 'Licitação')
                     = to_tsvector('search.portuguese_unaccent', 'licitacao')
                """,
                Boolean.class))
        .isTrue();
  }

  @Test
  void aTableCreatedLaterBytheMigrationOwnerIsAlsoUsable() throws Exception {
    jdbc.execute("CREATE TABLE search.it_later (id int PRIMARY KEY)");
    try (var connection = asReadWriter();
        var statement = connection.createStatement()) {
      assertThat(statement.executeUpdate("INSERT INTO search.it_later VALUES (1)")).isEqualTo(1);
      statement.execute("TRUNCATE search.it_later");
    } finally {
      jdbc.execute("DROP TABLE search.it_later");
    }
  }

  @Test
  void migratingAnotherDatabaseOfTheSameClusterReusesTheExistingRole() {
    jdbc.execute("CREATE DATABASE it_second");
    try {
      var url =
          SharedPostgres.INSTANCE
              .getJdbcUrl()
              .replace("/" + SharedPostgres.INSTANCE.getDatabaseName(), "/it_second");
      var result =
          Flyway.configure()
              .dataSource(
                  url, SharedPostgres.INSTANCE.getUsername(), SharedPostgres.INSTANCE.getPassword())
              .placeholders(Map.of("readWriterPassword", password))
              .load()
              .migrate();
      assertThat(result.success).isTrue();
    } finally {
      jdbc.execute("DROP DATABASE it_second WITH (FORCE)");
    }
  }
}
