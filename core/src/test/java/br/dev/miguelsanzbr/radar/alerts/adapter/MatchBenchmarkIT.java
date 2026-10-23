package br.dev.miguelsanzbr.radar.alerts.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.MatchProcurementUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.ReverseSearch;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cost of matching one procurement against 10 000 active saved searches (ADR-0015): the p50 of 50
 * runs, printed for docs/benchmarks/alerts/dedup.md. The searches are one or two words from a
 * vocabulary of about 100 words, a third of them also filtered by UF; about one in a hundred
 * matches.
 */
class MatchBenchmarkIT extends PostgresIT {

  private static final int SEARCHES = 10_000;
  private static final int RUNS = 50;
  private static final String VOCABULARY =
      "notebooks,educacao,medicamentos,veiculos,combustivel,merenda,uniformes,limpeza,papel,tinta,"
          + "mobiliario,obras,pavimentacao,asfalto,iluminacao,software,licenca,manutencao,ar,"
          + "condicionado,equipamentos,hospitalares,seringas,luvas,reagentes,ambulancia,pneus,"
          + "oleo,lubrificante,gas,alimentos,carne,frutas,verduras,cestas,agua,mineral,copos,"
          + "descartaveis,toner,cartuchos,computadores,monitores,impressoras,redes,cabos,"
          + "telefonia,internet,vigilancia,portaria,jardinagem,dedetizacao,reforma,pintura,"
          + "eletrica,hidraulica,cimento,areia,brita,tijolos,ferramentas,parafusos,tubos,"
          + "conexoes,valvulas,bombas,motores,geradores,baterias,lampadas,reatores,transformadores,"
          + "postes,fios,semaforos,placas,sinalizacao,brinquedos,livros,instrumentos,musicais,"
          + "esportivos,bolas,uniformes,fardamento,calcados,roupas,cama,mesa,banho,colchoes,"
          + "geladeiras,fogoes,freezers,microondas,ventiladores,bebedouros,extintores,vidros,"
          + "madeira";

  @Autowired MatchProcurementUseCase useCase;
  @Autowired ReverseSearch reverseSearch;

  @AfterEach
  void leaveNoSearchesBehind() {
    cleanDatabase();
  }

  private void insertSearches() {
    jdbc.sql(
            """
            INSERT INTO alerts.subscriber (id, email, consent_at, verified_at, created_at)
            SELECT gen_random_uuid(), 'bench' || g || '@matcher.test', now(), now(), now()
            FROM generate_series(1, 1000) g
            """)
        .update();
    jdbc.sql(
            """
            WITH vocabulary AS (
                   SELECT string_to_array(:vocabulary, ',') AS words,
                          array_length(string_to_array(:vocabulary, ','), 1) AS size),
                 subscribers AS (SELECT id, row_number() OVER () AS n FROM alerts.subscriber)
            INSERT INTO alerts.saved_search
              (id, subscriber_id, name, query_text, filters, frequency, active, created_at)
            SELECT gen_random_uuid(), s.id, 'bench ' || g,
                   v.words[1 + g % v.size]
                     || CASE WHEN g % 2 = 0 THEN ' ' || v.words[1 + (g * 7 + 3) % v.size] ELSE '' END,
                   CASE WHEN g % 3 = 0
                        THEN jsonb_build_object('uf', (ARRAY['SC','SP','RJ','MG','BA','PR','RS','PE','CE'])[1 + g % 9])
                        ELSE '{}'::jsonb END,
                   CASE WHEN g % 5 = 0 THEN 'DAILY' ELSE 'INSTANT' END, true, now()
            FROM generate_series(1, :searches) g CROSS JOIN vocabulary v
            JOIN subscribers s ON s.n = 1 + (g % 1000)
            """)
        .param("vocabulary", VOCABULARY)
        .param("searches", SEARCHES)
        .update();
    jdbc.sql("ANALYZE alerts.saved_search").update();
  }

  private static long p50(ArrayList<Long> nanos) {
    nanos.sort(Long::compare);
    return nanos.get(nanos.size() / 2);
  }

  private static String millis(long nanos) {
    return String.format("%.1f ms", nanos / 1_000_000.0);
  }

  @Test
  void matchingOneEventAgainstTenThousandActiveSearches() {
    insertSearches();
    assertThat(jdbc.sql("SELECT count(*) FROM alerts.saved_search").query(Long.class).single())
        .isEqualTo(SEARCHES);
    var matches = reverseSearch.find(Facts.procurement().build(), Facts.NOW).size();
    assertThat(matches).isPositive();

    var queryOnly = new ArrayList<Long>();
    var fullFlow = new ArrayList<Long>();
    for (var run = 0; run < RUNS; run++) {
      var facts = Facts.procurement().id("83021808000182-1-%06d/2026".formatted(run + 1)).build();
      var started = System.nanoTime();
      reverseSearch.find(facts, Facts.NOW);
      queryOnly.add(System.nanoTime() - started);

      var other = Facts.procurement().id("83021808000182-1-%06d/2027".formatted(run + 1)).build();
      started = System.nanoTime();
      var alerts = useCase.onDiscovered(UUID.randomUUID(), other);
      fullFlow.add(System.nanoTime() - started);
      assertThat(alerts).isEqualTo(matches);
    }

    System.out.printf(
        "ALERTS-BENCH searches=%d matchesPerEvent=%d runs=%d queryP50=%s queryMax=%s"
            + " fullFlowP50=%s fullFlowMax=%s%n",
        SEARCHES,
        matches,
        RUNS,
        millis(p50(queryOnly)),
        millis(queryOnly.stream().mapToLong(Long::longValue).max().orElseThrow()),
        millis(p50(fullFlow)),
        millis(fullFlow.stream().mapToLong(Long::longValue).max().orElseThrow()));
    assertThat(TimeUnit.NANOSECONDS.toMillis(p50(queryOnly)))
        .as("query p50 in ms, a sanity bound rather than a promise")
        .isLessThan(5_000);
  }
}
