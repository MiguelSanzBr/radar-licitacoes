package br.dev.miguelsanzbr.radar.synth;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.postgresql.PGConnection;

/**
 * Loads the read model with {@code COPY}: each batch goes into a temp staging table, then one
 * {@code INSERT ... SELECT} per table computes the search vector with the expression of the
 * projector (A = object, B = organ and unit, C = municipality and modality, config {@code
 * search.portuguese_unaccent}). A row that exists is left alone, so rerunning a seed is harmless.
 * The stats table is not touched: it belongs to the projector and to the replay.
 */
enum CopyLoader {
  ; // no constants: a holder of static methods that cannot be instantiated

  private static final int CHUNK_ROWS = 1_000;

  private static final String STAGE =
      """
      CREATE TEMP TABLE IF NOT EXISTS synth_stage (
        id text, object text, organ text, unit text, uf text, ibge text, municipality text,
        modality_id integer, modality_name text, status text, estimated_value numeric,
        value_band smallint, opening timestamptz, closing timestamptz, published timestamptz,
        updated timestamptz, detail text) ON COMMIT DELETE ROWS
      """;

  private static final String COPY = "COPY synth_stage FROM STDIN WITH (FORMAT csv)";

  private static final String INSERT_SEARCH =
      """
      INSERT INTO search.procurement_search_view
        (numero_controle_pncp, search_vector, object_description, organ_name, unit_name, uf,
         ibge_code, municipality_name, modality_id, status, estimated_value, value_band,
         proposal_opening_at, proposal_closing_at, published_at, pncp_updated_at, source_version,
         projected_at, source)
      SELECT id,
        setweight(to_tsvector('search.portuguese_unaccent', left(object, 100000)), 'A')
          || setweight(to_tsvector('search.portuguese_unaccent', organ || ' ' || coalesce(unit, '')), 'B')
          || setweight(to_tsvector('search.portuguese_unaccent',
               coalesce(municipality, '') || ' ' || modality_name), 'C'),
        object, organ, unit, uf, ibge, municipality, modality_id::smallint, status, estimated_value,
        value_band, opening, closing, published, updated, 1, now(), 'synthetic'
      FROM synth_stage
      ON CONFLICT (numero_controle_pncp) DO NOTHING
      """;

  private static final String INSERT_DETAIL =
      """
      INSERT INTO search.procurement_detail_view
        (numero_controle_pncp, detail, etag, source_version, updated_at)
      SELECT id, detail::jsonb,
        encode(sha256(convert_to(detail::jsonb::text, 'UTF8')), 'hex'), 1, now()
      FROM synth_stage
      ON CONFLICT (numero_controle_pncp) DO NOTHING
      """;

  /** One transaction per batch; returns how many search rows were inserted. */
  static long load(Connection connection, Generator generator, long count, int batch)
      throws SQLException {
    var copyApi = connection.unwrap(PGConnection.class).getCopyAPI();
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement.execute(STAGE);
      long inserted = 0;
      for (long done = 0; done < count; done += batch) {
        int rows = (int) Math.min(batch, count - done);
        var copy = copyApi.copyIn(COPY);
        for (int sent = 0; sent < rows; sent += CHUNK_ROWS) {
          var chunk = new StringBuilder();
          for (int i = Math.min(CHUNK_ROWS, rows - sent); i > 0; i--) {
            chunk.append(row(generator.next()));
          }
          var bytes = chunk.toString().getBytes(StandardCharsets.UTF_8);
          copy.writeToCopy(bytes, 0, bytes.length);
        }
        copy.endCopy();
        inserted += statement.executeUpdate(INSERT_SEARCH);
        statement.executeUpdate(INSERT_DETAIL);
        connection.commit();
      }
      return inserted;
    }
  }

  /** A CSV line in the column order of the staging table. */
  static String row(Synthetic s) {
    String[] cells = {
      s.id(),
      s.objectDescription(),
      s.organName(),
      s.unitName(),
      s.uf(),
      s.ibgeCode(),
      s.municipality(),
      Integer.toString(s.modality().id()),
      s.modality().name(),
      s.status(),
      s.estimatedValue() == null ? null : s.estimatedValue().toPlainString(),
      Short.toString(s.valueBand()),
      Objects.toString(s.openingAt(), null),
      Objects.toString(s.closingAt(), null),
      s.publishedAt().toString(),
      s.pncpUpdatedAt().toString(),
      s.detail()
    };
    var line = new StringBuilder(2_048);
    for (var cell : cells) {
      field(line, cell);
      line.append(',');
    }
    line.setLength(line.length() - 1);
    return line.append('\n').toString();
  }

  /** Quoted, so a comma or a quote in a text is data; null is the unquoted empty field. */
  static void field(StringBuilder line, String value) {
    if (value != null) {
      line.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
  }
}
