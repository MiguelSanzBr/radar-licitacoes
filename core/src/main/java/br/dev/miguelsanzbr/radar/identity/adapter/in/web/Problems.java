package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Writes an RFC 9457 problem straight to the response, for answers given before any handler. */
public final class Problems {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Problems() {}

  public static void write(
      HttpServletResponse response, int status, String code, String title, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    JSON.writeValue(
        response.getOutputStream(),
        Map.of("type", DOCS + code + ".md", "title", title, "status", status, "detail", detail));
  }
}
