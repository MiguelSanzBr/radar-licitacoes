package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import br.dev.miguelsanzbr.radar.search.application.GroupBy;
import br.dev.miguelsanzbr.radar.search.application.StatsReadModel;
import br.dev.miguelsanzbr.radar.search.application.StatsRow;
import java.math.BigDecimal;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Sums the buckets of {@code procurement_stats_view}; it never reads the search view. */
@Component
class JooqStatsReadModel implements StatsReadModel {

  private final DSLContext dsl;

  JooqStatsReadModel(@Qualifier("searchDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<StatsRow> aggregate(GroupBy groupBy) {
    var column = column(groupBy);
    var key = groupBy == GroupBy.MONTH ? "to_char(month, 'YYYY-MM')" : column + "::text";
    return dsl.resultQuery(
            "SELECT "
                + key
                + " AS k, sum(count)::bigint AS c, sum(estimated_sum) AS s"
                + " FROM search.procurement_stats_view GROUP BY "
                + column
                + " ORDER BY "
                + column)
        .fetch(
            r ->
                new StatsRow(
                    r.get("k", String.class),
                    r.get("c", Long.class),
                    r.get("s", BigDecimal.class)));
  }

  private static String column(GroupBy groupBy) {
    return switch (groupBy) {
      case UF -> "uf";
      case MODALITY -> "modality_id";
      case MONTH -> "month";
      case VALUE_BAND -> "value_band";
    };
  }
}
