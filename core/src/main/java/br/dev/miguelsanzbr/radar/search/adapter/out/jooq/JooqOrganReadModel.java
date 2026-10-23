package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import br.dev.miguelsanzbr.radar.search.application.OrganMatch;
import br.dev.miguelsanzbr.radar.search.application.OrganReadModel;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Organ names by trigram word similarity over {@code procurement_search_view}. Organ names are long
 * and the text is usually one or two words with a typo, so the whole-string similarity of {@code %}
 * would rarely reach the threshold; {@code <%} compares the text with the best matching part of the
 * name. It is served by {@code psv_organ_trgm} as well, so the match is on {@code organ_name} as
 * stored: case is ignored by pg_trgm, accents are not ({@code unaccent} is not immutable, so an
 * index over it is not possible). The threshold is set for the transaction only.
 */
@Component
class JooqOrganReadModel implements OrganReadModel {

  private final DSLContext dsl;
  private final TransactionTemplate transaction;
  private final double threshold;

  JooqOrganReadModel(
      @Qualifier("searchReadDsl") DSLContext dsl,
      @Qualifier("searchTransactionManager") PlatformTransactionManager transactionManager,
      @Value("${radar.search.organs.similarity-threshold:0.3}") double threshold) {
    this.dsl = dsl;
    this.transaction = new TransactionTemplate(transactionManager);
    this.threshold = threshold;
  }

  @Override
  public List<OrganMatch> similarTo(String q, int limit) {
    return transaction.execute(
        tx -> {
          dsl.execute(
              "SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)",
              Double.toString(threshold));
          return query(q, limit)
              .fetch(
                  r -> new OrganMatch(r.get("organ_name", String.class), r.get("s", Float.class)));
        });
  }

  ResultQuery<Record> query(String q, int limit) {
    return dsl.resultQuery(
        """
        SELECT organ_name, word_similarity(?, organ_name) AS s
        FROM search.procurement_search_view
        WHERE ? <% organ_name
        GROUP BY organ_name
        ORDER BY s DESC, organ_name
        LIMIT ?
        """,
        q, q, limit);
  }
}
