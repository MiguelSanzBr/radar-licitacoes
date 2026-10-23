package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import br.dev.miguelsanzbr.radar.search.application.SearchPage;
import br.dev.miguelsanzbr.radar.search.application.SearchReadModel;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.Filters;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Keyset search over {@code procurement_search_view}. With text the order is {@code ts_rank_cd}
 * then id, both descending; without it, {@code published_at} then id. The headline is computed in
 * an outer query, so only the rows of the returned page pay for it.
 *
 * <p>The SQL is written out because the full-text operators have no jOOQ counterpart; the values
 * are always bound, never concatenated.
 */
@Component
class JooqSearchReadModel implements SearchReadModel {

  private static final String CONFIG = "'search.portuguese_unaccent'";
  private static final String HEADLINE_OPTIONS =
      "MaxFragments=2, MaxWords=20, MinWords=5, StartSel=<b>, StopSel=</b>";

  /** The projector keeps at most this much of the object in the vector; so does the headline. */
  private static final int INDEXED_CHARS = 100_000;

  /** The list carries a bounded excerpt of the object, not megabytes of it. */
  private static final int EXCERPT_CHARS = 1_000;

  private static final String COLUMNS =
      "v.numero_controle_pncp AS id, left(v.object_description, "
          + EXCERPT_CHARS
          + ") AS object_description, v.organ_name, v.unit_name, v.uf, v.municipality_name,"
          + " v.ibge_code, v.modality_id, v.status, v.estimated_value, v.proposal_opening_at,"
          + " v.proposal_closing_at, v.published_at";

  /** What the outer query keeps of {@code page}: everything but the full object text. */
  private static final String OUTER_COLUMNS =
      "page.id, page.object_description, page.organ_name, page.unit_name, page.uf,"
          + " page.municipality_name, page.ibge_code, page.modality_id, page.status,"
          + " page.estimated_value, page.proposal_opening_at, page.proposal_closing_at,"
          + " page.published_at, page.rank";

  /**
   * An open procurement takes proposals now: published, closing in the future, and already opened
   * (an unknown opening counts as opened, since many notices only state the closing).
   */
  private static final String OPEN =
      "v.status = 'PUBLISHED' AND v.proposal_closing_at > now()"
          + " AND (v.proposal_opening_at IS NULL OR v.proposal_opening_at <= now())";

  private static final String RANK = "ts_rank_cd(v.search_vector, ?::tsquery)::float8";

  private final DSLContext dsl;

  JooqSearchReadModel(@Qualifier("searchDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public SearchPage search(SearchQuery query) {
    var statement = query(query);
    if (statement.isEmpty()) {
      return new SearchPage(List.of(), null);
    }
    var rows = statement.get().fetch();
    var items = rows.stream().limit(query.limit()).map(JooqSearchReadModel::result).toList();
    if (rows.size() <= query.limit()) {
      return new SearchPage(items, null);
    }
    var last = items.get(items.size() - 1);
    var next =
        query.hasText()
            ? new Cursor.ByRank(last.rank(), last.id())
            : new Cursor.ByPublished(last.publishedAt(), last.id());
    return new SearchPage(items, next);
  }

  /**
   * The statement of one page: {@code limit + 1} rows, to know whether a next page exists. Empty
   * when the text has no searchable term (only stopwords or punctuation): nothing can match.
   *
   * <p>The text is compiled to a tsquery in a first, trivial statement and passed on as a value.
   * The planner then sees a constant, estimates the match from the column statistics and picks the
   * GIN index when it pays off, and no row re-parses the text for its rank.
   */
  Optional<ResultQuery<Record>> query(SearchQuery query) {
    var binds = new ArrayList<Object>();
    if (!query.hasText()) {
      return Optional.of(dsl.resultQuery(byDate(query, binds), binds.toArray()));
    }
    var tsquery =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT websearch_to_tsquery(" + CONFIG + ", ?)::text", query.text())
                .fetchOne(0, String.class));
    return tsquery.isEmpty()
        ? Optional.empty()
        : Optional.of(dsl.resultQuery(ranked(query, tsquery, binds), binds.toArray()));
  }

  private static String byDate(SearchQuery query, List<Object> binds) {
    var where = new StringBuilder(filters(query.filters(), binds));
    if (query.cursor() instanceof Cursor.ByPublished after) {
      where.append(" AND (v.published_at, v.numero_controle_pncp) < (?::timestamptz, ?)");
      binds.add(after.publishedAt().atOffset(ZoneOffset.UTC));
      binds.add(after.id());
    }
    binds.add(query.limit() + 1);
    return "SELECT "
        + COLUMNS
        + ", NULL::text AS snippet, NULL::float8 AS rank FROM search.procurement_search_view v"
        + " WHERE "
        + where
        + " ORDER BY v.published_at DESC, v.numero_controle_pncp DESC LIMIT ?";
  }

  /** The rank is cast to float8 so the cursor compares exactly what the client was given. */
  private static String ranked(SearchQuery query, String tsquery, List<Object> binds) {
    binds.add(tsquery);
    binds.add(HEADLINE_OPTIONS);
    binds.add(tsquery);
    binds.add(tsquery);
    var where = new StringBuilder(filters(query.filters(), binds));
    if (query.cursor() instanceof Cursor.ByRank after) {
      where.append(" AND (").append(RANK).append(", v.numero_controle_pncp) < (?::float8, ?)");
      binds.add(tsquery);
      binds.add(after.rank());
      binds.add(after.id());
    }
    binds.add(query.limit() + 1);
    return "SELECT "
        + OUTER_COLUMNS
        + ", ts_headline("
        + CONFIG
        + ", left(page.full_object, "
        + INDEXED_CHARS
        + "), ?::tsquery, ?) AS snippet FROM (SELECT "
        + COLUMNS
        + ", v.object_description AS full_object, "
        + RANK
        + " AS rank FROM search.procurement_search_view v WHERE v.search_vector @@ ?::tsquery AND "
        + where
        + " ORDER BY rank DESC, id DESC LIMIT ?) page ORDER BY page.rank DESC, page.id DESC";
  }

  private static String filters(Filters f, List<Object> binds) {
    var sql = new StringBuilder("true");
    if (f.uf() != null) {
      sql.append(" AND v.uf = ?::bpchar");
      binds.add(f.uf().name());
    }
    if (f.ibge() != null) {
      sql.append(" AND v.ibge_code = ?::bpchar");
      binds.add(f.ibge().value());
    }
    if (f.modalityId() != null) {
      sql.append(" AND v.modality_id = ?");
      binds.add(f.modalityId());
    }
    if (f.minValue() != null) {
      sql.append(" AND v.estimated_value >= ?");
      binds.add(f.minValue().amount());
    }
    if (f.maxValue() != null) {
      sql.append(" AND v.estimated_value <= ?");
      binds.add(f.maxValue().amount());
    }
    if (f.openOnly()) {
      sql.append(" AND ").append(OPEN);
    }
    if (f.status() != null) {
      sql.append(" AND v.status = ?");
      binds.add(f.status());
    }
    return sql.toString();
  }

  private static RankedResult result(Record r) {
    var value = r.get("estimated_value", BigDecimal.class);
    return new RankedResult(
        r.get("id", String.class),
        r.get("object_description", String.class),
        r.get("snippet", String.class),
        r.get("organ_name", String.class),
        r.get("unit_name", String.class),
        r.get("uf", String.class),
        r.get("municipality_name", String.class),
        r.get("ibge_code", String.class),
        r.get("modality_id", Integer.class),
        r.get("status", String.class),
        value == null ? null : new Money(value),
        instant(r, "proposal_opening_at"),
        instant(r, "proposal_closing_at"),
        instant(r, "published_at"),
        r.get("rank", Double.class));
  }

  private static Instant instant(Record r, String column) {
    var at = r.get(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
