package br.dev.miguelsanzbr.radar.identity.domain;

/** Requests per minute of each kind of caller. */
public record RateLimits(int free, int pro, int anonymous) {

  /** Every limit is per minute: the bucket refills its whole capacity in this time. */
  public static final long WINDOW_MS = 60_000;

  public int of(Tier tier) {
    return switch (tier) {
      case FREE -> free;
      case PRO -> pro;
    };
  }
}
