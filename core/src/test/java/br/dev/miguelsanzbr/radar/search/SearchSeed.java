package br.dev.miguelsanzbr.radar.search;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.eventId;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situation;

import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A thousand realistic procurements for the query tests. They go through the projector's own store,
 * so the vectors come from the production SQL and not from a hand-written copy.
 */
public final class SearchSeed {

  public static final Instant BASE = Instant.parse("2026-06-01T12:00:00.123456Z");
  public static final String RELEVANT_BY_OBJECT = id(1);
  public static final String RELEVANT_BY_ORGAN = id(2);

  private static final String[] OBJECTS = {
    "Contratação de empresa especializada em serviços de limpeza e conservação predial",
    "Aquisição de material de consumo para merenda escolar",
    "Registro de preços para aquisição de medicamentos e insumos hospitalares",
    "Manutenção preventiva e corretiva da frota de veículos municipais",
    "Execução de obras de pavimentação asfáltica em vias urbanas",
    "Abertura de licitação para locação de impressoras e serviços de outsourcing",
    "Fornecimento de combustíveis e lubrificantes para a frota",
    "Contratação de serviços de vigilância armada e monitoramento eletrônico",
    "Aquisição de equipamentos de informática, computadores e notebooks",
    "Serviços de coleta de resíduos sólidos e limpeza urbana"
  };
  private static final String[] SUFFIXES = {
    "urgente", "complementar", "anual", "lote único", "emergencial", "limpeza", "limpeza"
  };
  private static final String[] UFS = {"SP", "MG", "RJ", "BA", "PR"};
  private static final String[] CITIES = {
    "São Paulo", "Belo Horizonte", "Rio de Janeiro", "Salvador", "Curitiba"
  };
  private static final String[] IBGE = {"3550308", "3106200", "3304557", "2927408", "4106902"};
  private static final String[] ORGANS = {
    "Prefeitura Municipal de ", "Câmara Municipal de ", "Secretaria de Saúde de "
  };

  private SearchSeed() {}

  /** 998 random rows plus the two of the relevance test; deterministic for a given {@code now}. */
  public static List<ProcurementView> views(Instant now) {
    var random = new Random(42);
    var views = new ArrayList<ProcurementView>();
    views.add(
        view(
            1,
            "Aquisição de ambulância equipada para o SAMU",
            "Prefeitura Municipal de Campinas",
            0,
            now));
    views.add(
        view(2, "Aquisição de mobiliário escolar", "Secretaria da Ambulância Municipal", 1, now));
    for (var i = 3; i <= 1000; i++) {
      var object = new StringBuilder(OBJECTS[random.nextInt(OBJECTS.length)]);
      for (var extra = random.nextInt(4); extra > 0; extra--) {
        object.append(' ').append(SUFFIXES[random.nextInt(SUFFIXES.length)]);
      }
      var city = i % UFS.length;
      views.add(view(i, object.toString(), ORGANS[i % ORGANS.length] + CITIES[city], city, now));
    }
    return views;
  }

  private static ProcurementView view(int n, String object, String organ, int place, Instant now) {
    var status = n % 13 == 0 ? "SUSPENDED" : n % 17 == 0 ? "REVOKED" : "PUBLISHED";
    var situationId = status.equals("PUBLISHED") ? 1 : status.equals("REVOKED") ? 2 : 4;
    var opening =
        n % 15 == 0
            ? null
            : n % 9 == 0 ? now.plus(Duration.ofDays(2)) : now.minus(Duration.ofDays(20));
    var closing = n % 11 == 0 ? null : now.plus(Duration.ofDays(n % 3 == 0 ? 10 : -10));
    // why: groups of four share the instant (ties for the keyset), with microseconds on purpose
    var published = BASE.minus(Duration.ofMinutes(n / 4)).plus(n / 4 % 9, ChronoUnit.MICROS);
    return new ProcurementView(
        id(n),
        status,
        situation(situationId),
        organ,
        "Setor " + n,
        UFS[place],
        CITIES[place],
        IBGE[place],
        n % 8 + 1,
        "Modalidade " + (n % 8 + 1),
        n % 7 == 0 ? null : new BigDecimal((n % 50 + 1) * 10_000 + ".00"),
        opening,
        closing,
        published,
        published,
        object,
        "{}");
  }

  public static void load(ProjectionStore store, List<ProcurementView> views) {
    var offset = 0L;
    for (var view : views) {
      store.project(
          new ProcurementEvent.Discovered(eventId("seed-" + view.id()), view.id(), 1, BASE, view),
          offset++);
    }
  }
}
