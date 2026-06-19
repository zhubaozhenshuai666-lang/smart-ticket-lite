-- Multi token bucket rate limiter.
--
-- KEYS[1..N] = rate limit bucket keys
-- ARGV[1] = now millis
-- ARGV[2] = key ttl seconds
-- ARGV[3] = bucket count N
-- For each bucket i:
--   ARGV[4 + (i - 1) * 3] = capacity
--   ARGV[5 + (i - 1) * 3] = refill rate per second
--   ARGV[6 + (i - 1) * 3] = requested tokens
--
-- Return:
--   1 = all buckets allowed and tokens deducted
--   0 = at least one bucket rejected; refreshed tokens are still saved
--  -1 = invalid arguments
local now_millis = tonumber(ARGV[1])
local key_ttl_seconds = tonumber(ARGV[2])
local bucket_count = tonumber(ARGV[3])

if not now_millis or now_millis < 0
        or not key_ttl_seconds or key_ttl_seconds <= 0
        or not bucket_count or bucket_count <= 0
        or #KEYS ~= bucket_count
        or #ARGV ~= 3 + bucket_count * 3 then
    return -1
end

local function load_tokens(bucket_key, capacity, refill_rate)
    local stored_tokens = redis.call('HGET', bucket_key, 'tokens')
    local stored_last_refill_time = redis.call('HGET', bucket_key, 'last_refill_time')
    local tokens = capacity
    local last_refill_time = now_millis

    if stored_tokens and stored_last_refill_time then
        tokens = tonumber(stored_tokens)
        last_refill_time = tonumber(stored_last_refill_time)
        if not tokens or not last_refill_time then
            tokens = capacity
            last_refill_time = now_millis
        end
    end

    local elapsed_millis = now_millis - last_refill_time
    if elapsed_millis < 0 then
        elapsed_millis = 0
    end

    local refill_tokens = elapsed_millis / 1000 * refill_rate
    return math.min(capacity, tokens + refill_tokens)
end

local function save_tokens(bucket_key, tokens)
    redis.call('HSET', bucket_key, 'tokens', tostring(tokens), 'last_refill_time', tostring(now_millis))
    redis.call('EXPIRE', bucket_key, tostring(key_ttl_seconds))
end

local capacities = {}
local refill_rates = {}
local requested_tokens = {}
local loaded_tokens = {}
local allowed = true

for index = 1, bucket_count do
    local arg_offset = 4 + (index - 1) * 3
    local capacity = tonumber(ARGV[arg_offset])
    local refill_rate = tonumber(ARGV[arg_offset + 1])
    local requested = tonumber(ARGV[arg_offset + 2])
    if not capacity or capacity <= 0
            or not refill_rate or refill_rate <= 0
            or not requested or requested <= 0 then
        return -1
    end
    capacities[index] = capacity
    refill_rates[index] = refill_rate
    requested_tokens[index] = requested
    loaded_tokens[index] = load_tokens(KEYS[index], capacity, refill_rate)
    if loaded_tokens[index] < requested then
        allowed = false
    end
end

for index = 1, bucket_count do
    local remaining = loaded_tokens[index]
    if allowed then
        remaining = remaining - requested_tokens[index]
    end
    save_tokens(KEYS[index], remaining)
end

if allowed then
    return 1
end
return 0
