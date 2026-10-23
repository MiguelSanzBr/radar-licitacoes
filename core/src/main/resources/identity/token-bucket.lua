-- Token bucket, one atomic step: refill by the time elapsed, take one token, report what is left.
-- KEYS[1] bucket; ARGV[1] capacity (tokens per window), ARGV[2] window (ms),
-- ARGV[3] now (ms), optional: only tests pass it, otherwise the time is the one of the Redis server,
-- so every application instance reads the same clock.
-- Returns {allowed (1 or 0), level}.
--
-- The level counts 1/window of a token: a request costs `window` and every elapsed millisecond
-- refills `capacity`, so nothing is ever rounded and the bucket never lends or loses a token.
-- TokenBucketModel (test sources) is the same arithmetic in Java and is the oracle of the tests.
local capacity = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
if now == nil then
  local time = redis.call('TIME')
  now = time[1] * 1000 + math.floor(time[2] / 1000)
end
local full = capacity * window

local state = redis.call('HMGET', KEYS[1], 'level', 'ts')
local level = tonumber(state[1])
local ts = tonumber(state[2])
if level == nil then
  level = full
  ts = now
end

-- A time behind the stored one (a test clock, or a Redis failover to a replica a bit behind) refills
-- nothing and moves nothing back.
if now > ts then
  level = level + (now - ts) * capacity
  ts = now
end
if level > full then
  level = full
end

local allowed = 0
if level >= window then
  level = level - window
  allowed = 1
end

redis.call('HSET', KEYS[1], 'level', level, 'ts', ts)
-- The state is the same as an absent key once the bucket is full, so it can expire by then.
redis.call('PEXPIRE', KEYS[1], math.ceil((full - level) / capacity) + 1000)
return {allowed, level}
