package br.dev.miguelsanzbr.radar.probe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

enum Hashes {
  ; // no constants: a holder of static methods that cannot be instantiated

  static String sha256(String text) {
    return hex("SHA-256", text);
  }

  static String hex(String algorithm, String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
