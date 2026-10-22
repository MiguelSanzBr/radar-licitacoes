package br.dev.miguelsanzbr.radar.probe;

import java.util.List;
import java.util.Map;

/** Canned PNCP-like answers keyed by URL path. */
final class FakeApi implements Transport {

  int totalPages = 20;
  Reply robots = new Reply(-1, Map.of(), "", 0, 5, "java.io.IOException: Connection reset");

  static Reply json(int status, String body) {
    return new Reply(
        status,
        Map.of("content-type", List.of("application/json")),
        body,
        body.length(),
        120,
        null);
  }

  static String item(int i) {
    return """
        {"numeroControlePNCP":"11111111000191-1-%06d/2026","orgaoEntidade":{"cnpj":"11111111000191","razaoSocial":"X"},\
        "anoCompra":2026,"sequencialCompra":%d,"dataAtualizacao":"2026-10-22T10:00:0%d",\
        "dataAtualizacaoGlobal":"2026-10-22T10:00:1%d","modalidadeId":6,"informacaoComplementar":null}"""
        .formatted(i, i, i, i);
  }

  static String page(int total, int pages, String... items) {
    return "{\"data\":[%s],\"totalRegistros\":%d,\"totalPaginas\":%d,\"numeroPagina\":1,\"empty\":false}"
        .formatted(String.join(",", items), total, pages);
  }

  @Override
  public Reply send(String url) {
    if (url.endsWith("/robots.txt")) {
      return robots;
    }
    if (url.contains("/contratacoes/")) {
      return json(
          200,
          page(
              1000, totalPages, totalPages == 0 ? new String[0] : new String[] {item(1), item(2)}));
    }
    if (url.contains("/contratos")) {
      return json(200, page(5, 1, "{\"niFornecedor\":\"12345678901\",\"cpfResponsavel\":null}"));
    }
    if (url.contains("/itens")) {
      return json(200, "[{\"numeroItem\":1},{\"numeroItem\":2}]");
    }
    if (url.contains("/atas")) {
      return json(200, page(3, 1, "{\"numeroControlePNCP\":\"a\"}"));
    }
    return json(200, "{\"numeroControlePNCP\":\"d\",\"cpf\":\"1\"}");
  }
}
