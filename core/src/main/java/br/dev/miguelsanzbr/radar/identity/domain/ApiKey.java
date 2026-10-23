package br.dev.miguelsanzbr.radar.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * An active API key. The secret ({@code rk_<prefix>_<32 random bytes in base64url>}) is shown once
 * when issued; only its SHA-256 (hex) and the 8-character prefix, which names the key to its owner,
 * are kept. The owner is the identity provider's subject, an opaque id.
 */
public record ApiKey(
    UUID id, String ownerSubject, String keyHash, String prefix, Tier tier, Instant createdAt) {

  private static final Pattern SHAPE = Pattern.compile("rk_[0-9a-f]{8}_[A-Za-z0-9_-]{43}");

  /** The key as stored and the secret that only the response of the creation carries. */
  public record Issued(ApiKey key, String secret) {

    /** Keeps the secret out of logs and exception messages. */
    @Override
    public String toString() {
      return "Issued[key=" + key + ", secret=<redacted>]";
    }
  }

  /** Whether the text has the shape of an issued secret; anything else is no key, no lookup. */
  public static boolean wellFormed(String text) {
    return SHAPE.matcher(text).matches();
  }

  public static Issued issue(
      UUID id, String ownerSubject, Tier tier, Instant now, RandomGenerator random) {
    var prefixBytes = new byte[4];
    var secretBytes = new byte[32];
    random.nextBytes(prefixBytes);
    random.nextBytes(secretBytes);
    var prefix = HexFormat.of().formatHex(prefixBytes);
    var secret =
        "rk_" + prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    return new Issued(new ApiKey(id, ownerSubject, hash(secret), prefix, tier, now), secret);
  }

  /** SHA-256 in hex. Enough for a secret of 256 random bits: no salt or slow hash is needed. */
  public static String hash(String secret) {
    return digest("SHA-256", secret);
  }

  // The algorithm is a parameter only so a test can reach the unreachable-in-practice catch.
  static String digest(String algorithm, String text) {
    try {
      var bytes =
          MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("missing digest algorithm [" + algorithm + "]", e);
    }
  }
}
