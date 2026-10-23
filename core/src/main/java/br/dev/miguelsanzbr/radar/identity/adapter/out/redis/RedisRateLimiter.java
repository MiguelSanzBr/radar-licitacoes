package br.dev.miguelsanzbr.radar.identity.adapter.out.redis;

import br.dev.miguelsanzbr.radar.identity.application.RateLimiterUnavailableException;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimiter;
import br.dev.miguelsanzbr.radar.identity.domain.Decision;
import br.dev.miguelsanzbr.radar.identity.domain.RateLimits;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Token bucket in Redis, one Lua script per request ({@code identity/token-bucket.lua}), so the
 * refill, the take and the answer are a single atomic step even across application instances. The
 * time is the one of the Redis server, so the instances cannot disagree about it.
 */
public class RedisRateLimiter implements RateLimiter {

  private static final RedisScript<List<Long>> SCRIPT = script();

  private final StringRedisTemplate redis;
  private final Clock clock;

  /** Time from the Redis server. */
  public RedisRateLimiter(StringRedisTemplate redis) {
    this(redis, null);
  }

  /** Time from {@code clock}, for tests that move it by hand; null means the Redis server's. */
  public RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
  }

  @SuppressWarnings("unchecked") // the script returns {allowed, level}: Redis numbers are Long
  private static RedisScript<List<Long>> script() {
    var script = new DefaultRedisScript<List<Long>>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("identity/token-bucket.lua")));
    script.setResultType((Class<List<Long>>) (Class<?>) List.class);
    return script;
  }

  @Override
  public Decision take(String bucket, int capacity) {
    try {
      var arguments = new ArrayList<String>();
      arguments.add(String.valueOf(capacity));
      arguments.add(String.valueOf(RateLimits.WINDOW_MS));
      if (clock != null) {
        arguments.add(String.valueOf(clock.millis()));
      }
      var result = redis.execute(SCRIPT, List.of("rl:" + bucket), arguments.toArray());
      return Decision.of(result.get(0) == 1L, result.get(1), capacity, RateLimits.WINDOW_MS);
    } catch (DataAccessException e) {
      throw new RateLimiterUnavailableException(e);
    }
  }
}
