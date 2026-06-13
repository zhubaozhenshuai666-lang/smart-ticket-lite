-- Redis 令牌桶限流脚本。
--
-- 为什么阶段 2 下单入口优先使用令牌桶？
-- 固定窗口计数器在窗口边界会有突刺：前一个窗口最后一秒和下一个窗口第一秒都打满时，
-- 短时间内可能放过接近 2 倍阈值的请求。滑动窗口可以更平滑，但如果用 ZSET 记录 API / 票档
-- 这种高频大阈值维度的每次请求时间戳，会带来明显内存膨胀。令牌桶只保存 tokens 和 last_refill_time，
-- 更适合本项目的下单入口保护。
--
-- KEYS[1] = rate limit bucket key
--   例如 rate:limit:user:{userId}:order、rate:limit:ip:{ip}:order、rate:limit:api:{apiName}。
--
-- ARGV[1] = capacity
--   桶容量，也就是允许短时间突发的最大 token 数。
-- ARGV[2] = refillRatePerSecond
--   每秒补充 token 的速度，决定长期稳定放行速率。
-- ARGV[3] = requestedTokens
--   本次请求要消耗的 token 数，当前下单入口固定为 1。
-- ARGV[4] = nowMillis
--   Java 侧传入的当前时间毫秒，用于计算距离上次补充过去了多久。
-- ARGV[5] = keyTtlSeconds
--   限流 key 的过期时间，避免低频用户/IP 的桶永久堆积在 Redis。
--
-- 返回码：
--  1 = ALLOWED，令牌足够并已扣减。
--  0 = REJECTED，令牌不足，请求需要被限流。
-- -1 = INVALID_ARGUMENT，配置或请求 token 非法。
--
-- 为什么必须用 Lua？
-- 令牌桶要先读当前 tokens 和 last_refill_time，再计算补充量，最后扣减并写回。
-- 如果这些步骤拆成多条 Redis 命令，高并发下多个请求会读到同一份旧 tokens，导致超放。
-- Lua 在 Redis 内部原子执行，保证同一个桶同一时刻只有一个请求完成计算和扣减。
local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate_per_second = tonumber(ARGV[2])
local requested_tokens = tonumber(ARGV[3])
local now_millis = tonumber(ARGV[4])
local key_ttl_seconds = tonumber(ARGV[5])

if not capacity or capacity <= 0
        or not refill_rate_per_second or refill_rate_per_second <= 0
        or not requested_tokens or requested_tokens <= 0
        or not now_millis or now_millis < 0
        or not key_ttl_seconds or key_ttl_seconds <= 0 then
    return -1
end

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

local refill_tokens = elapsed_millis / 1000 * refill_rate_per_second
tokens = math.min(capacity, tokens + refill_tokens)

if tokens >= requested_tokens then
    tokens = tokens - requested_tokens
    redis.call('HSET', bucket_key, 'tokens', tostring(tokens), 'last_refill_time', tostring(now_millis))
    redis.call('EXPIRE', bucket_key, tostring(key_ttl_seconds))
    return 1
end

redis.call('HSET', bucket_key, 'tokens', tostring(tokens), 'last_refill_time', tostring(now_millis))
redis.call('EXPIRE', bucket_key, tostring(key_ttl_seconds))
return 0
