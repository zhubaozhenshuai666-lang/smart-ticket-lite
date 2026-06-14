local first_key = KEYS[1]
local second_key = KEYS[2]

local first_capacity = tonumber(ARGV[1])
local first_refill_rate = tonumber(ARGV[2])
local first_requested = tonumber(ARGV[3])
local second_capacity = tonumber(ARGV[4])
local second_refill_rate = tonumber(ARGV[5])
local second_requested = tonumber(ARGV[6])
local now_millis = tonumber(ARGV[7])
local key_ttl_seconds = tonumber(ARGV[8])

if not first_capacity or first_capacity <= 0
        or not first_refill_rate or first_refill_rate <= 0
        or not first_requested or first_requested <= 0
        or not second_capacity or second_capacity <= 0
        or not second_refill_rate or second_refill_rate <= 0
        or not second_requested or second_requested <= 0
        or not now_millis or now_millis < 0
        or not key_ttl_seconds or key_ttl_seconds <= 0 then
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

local first_tokens = load_tokens(first_key, first_capacity, first_refill_rate)
local second_tokens = load_tokens(second_key, second_capacity, second_refill_rate)

if first_tokens < first_requested or second_tokens < second_requested then
    save_tokens(first_key, first_tokens)
    save_tokens(second_key, second_tokens)
    return 0
end

save_tokens(first_key, first_tokens - first_requested)
save_tokens(second_key, second_tokens - second_requested)
return 1
