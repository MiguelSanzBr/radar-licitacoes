package br.dev.miguelsanzbr.radar.probe;

import java.util.List;
import java.util.Map;

/** One HTTP outcome. A transport failure has status -1, no headers and the error text. */
public record Reply(
    int status,
    Map<String, List<String>> headers,
    String body,
    int bytes,
    long latencyMs,
    String error) {

  public Reply {
    headers = Map.copyOf(headers);
  }
}
