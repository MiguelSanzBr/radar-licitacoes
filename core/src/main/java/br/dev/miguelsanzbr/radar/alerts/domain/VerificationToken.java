package br.dev.miguelsanzbr.radar.alerts.domain;

import java.time.Duration;
import java.util.Base64;
import java.util.random.RandomGenerator;

/** E-mail verification token: the raw value goes in the link, only its SHA-256 is stored. */
public final class VerificationToken {

  public static final Duration TTL = Duration.ofHours(48);

  private VerificationToken() {}

  public static String newRaw(RandomGenerator random) {
    var bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static byte[] hash(String raw) {
    return Sha256.hash(raw);
  }
}
