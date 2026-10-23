package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The snippet is text written by the procuring organ with {@code <b>} around the matched terms. It
 * is cut at those two markers and nothing else, so the page can escape every piece and still mark
 * the terms: no part of it is ever rendered as markup.
 */
final class Snippets {

  /** A piece of the snippet; {@code marked} when it sat between the markers. */
  record Segment(String text, boolean marked) {}

  private static final Pattern MARKER = Pattern.compile("</?b>");

  private Snippets() {}

  static List<Segment> split(String snippet) {
    var segments = new ArrayList<Segment>();
    if (snippet == null) {
      return segments;
    }
    var matcher = MARKER.matcher(snippet);
    var marked = false;
    var from = 0;
    while (matcher.find()) {
      add(segments, snippet.substring(from, matcher.start()), marked);
      marked = matcher.group().length() == "<b>".length();
      from = matcher.end();
    }
    add(segments, snippet.substring(from), marked);
    return segments;
  }

  private static void add(List<Segment> segments, String text, boolean marked) {
    if (!text.isEmpty()) {
      segments.add(new Segment(text, marked));
    }
  }
}
