package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Validators for the cached answers. */
final class ETags {

  private ETags() {}

  /** Weak, because the same page may be serialized with different but equivalent bytes. */
  static String weak(String body) {
    return "W/\"" + sha256(body) + "\"";
  }

  /** {@code If-None-Match: *} matches any current representation; Spring does not implement it. */
  static boolean matchesAny(String ifNoneMatch) {
    return ifNoneMatch != null && ifNoneMatch.strip().equals("*");
  }

  static String sha256(String text) {
    return HexFormat.of().formatHex(digest("SHA-256", text));
  }

  // The algorithm is a parameter only so a test can reach the unreachable-in-practice catch.
  static byte[] digest(String algorithm, String text) {
    try {
      return MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("missing digest algorithm [" + algorithm + "]", e);
    }
  }
}
