package br.dev.miguelsanzbr.radar.search.domain;

/**
 * The control number has a slash ({@code CNPJ-1-SEQ/YEAR}), which cannot sit in a path segment: the
 * API shows it with a hyphen, and only the last hyphen goes back to a slash.
 */
public final class ProcurementIds {

  private ProcurementIds() {}

  public static String toPath(String id) {
    return id.replace('/', '-');
  }

  public static String fromPath(String path) {
    var last = path.lastIndexOf('-');
    return last < 0 ? path : path.substring(0, last) + '/' + path.substring(last + 1);
  }
}
