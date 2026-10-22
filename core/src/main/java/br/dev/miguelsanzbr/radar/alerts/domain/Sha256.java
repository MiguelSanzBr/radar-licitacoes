package br.dev.miguelsanzbr.radar.alerts.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 of UTF-8 text, used for token storage and idempotency request hashes. */
public final class Sha256 {

  private Sha256() {}

  public static byte[] hash(String text) {
    return digest("SHA-256", text);
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
