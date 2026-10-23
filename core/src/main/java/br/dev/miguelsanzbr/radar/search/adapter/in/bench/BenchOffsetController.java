package br.dev.miguelsanzbr.radar.search.adapter.in.bench;

import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Benchmark only (PR 2.7): the unfiltered list by {@code OFFSET}, the paging the keyset replaces,
 * so a depth can be timed both ways. It is not in the OpenAPI contract nor in the authorization
 * matrix, and the bean does not exist unless {@code radar.bench.offset-endpoint=true}. Besides the
 * rows it returns the keyset cursor after the last one, which is how the benchmark reaches a deep
 * page by keyset without walking to it.
 */
@RestController
@ConditionalOnProperty(name = "radar.bench.offset-endpoint", havingValue = "true")
class BenchOffsetController {

  private static final int MAX_LIMIT = 50;

  private final DSLContext dsl;

  BenchOffsetController(@Qualifier("searchReadDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  @GetMapping("/bench/offset")
  Map<String, Object> page(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
    int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
    var rows =
        dsl.resultQuery(
                """
                SELECT numero_controle_pncp AS id, left(object_description, 1000) AS object_description,
                       organ_name, unit_name, uf, municipality_name, ibge_code, modality_id, status,
                       estimated_value, proposal_opening_at, proposal_closing_at, published_at
                FROM search.procurement_search_view
                ORDER BY published_at DESC, numero_controle_pncp DESC
                LIMIT ? OFFSET ?
                """,
                size,
                (long) (Math.max(page, 1) - 1) * size)
            .fetch();
    var items =
        rows.stream()
            .<Map<String, Object>>map(
                r -> Map.of("id", ProcurementIds.toPath(r.get("id", String.class))))
            .toList();
    var body = new LinkedHashMap<String, Object>();
    body.put("items", items);
    if (!rows.isEmpty()) {
      var last = rows.get(rows.size() - 1);
      body.put(
          "nextCursor",
          new Cursor.ByPublished(
                  last.get("published_at", OffsetDateTime.class).toInstant(),
                  last.get("id", String.class))
              .encode());
    }
    return body;
  }
}
