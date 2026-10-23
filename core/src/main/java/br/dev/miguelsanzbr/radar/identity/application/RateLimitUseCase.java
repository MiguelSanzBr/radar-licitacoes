package br.dev.miguelsanzbr.radar.identity.application;

import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimitMetrics;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimiter;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import br.dev.miguelsanzbr.radar.identity.domain.Decision;
import br.dev.miguelsanzbr.radar.identity.domain.RateLimits;
import java.util.Optional;

/** Meters a public read: by API key when it carries one, else by the address of the caller. */
public class RateLimitUseCase {

  private final ApiKeys keys;
  private final RateLimiter limiter;
  private final RateLimits limits;
  private final RateLimitMetrics metrics;

  public RateLimitUseCase(
      ApiKeys keys, RateLimiter limiter, RateLimits limits, RateLimitMetrics metrics) {
    this.keys = keys;
    this.limiter = limiter;
    this.limits = limits;
    this.metrics = metrics;
  }

  /**
   * A key that is malformed, unknown or revoked is charged to the bucket of the address like any
   * anonymous read, so guessing keys is rate limited too: it ends in 429 once the address is out of
   * tokens and in {@link InvalidApiKeyException} before that.
   *
   * @param apiKey the secret of the {@code X-Api-Key} header, or null when the request has none
   * @return the verdict, or empty when the limiter is down: a public read stays up without it
   * @throws InvalidApiKeyException when a key was sent that is not an active one
   */
  public Optional<Decision> check(String apiKey, String clientAddress) {
    if (apiKey == null) {
      return meter("ip:" + clientAddress, limits.anonymous());
    }
    var key =
        ApiKey.wellFormed(apiKey) ? keys.findActive(ApiKey.hash(apiKey)) : Optional.<ApiKey>empty();
    if (key.isEmpty()) {
      var charged = meter("ip:" + clientAddress, limits.anonymous());
      if (charged.isPresent() && !charged.get().allowed()) {
        return charged;
      }
      throw new InvalidApiKeyException();
    }
    return meter("key:" + key.get().id(), limits.of(key.get().tier()));
  }

  private Optional<Decision> meter(String bucket, int capacity) {
    try {
      return Optional.of(limiter.take(bucket, capacity));
    } catch (RateLimiterUnavailableException e) {
      metrics.failedOpen(e);
      return Optional.empty();
    }
  }
}
