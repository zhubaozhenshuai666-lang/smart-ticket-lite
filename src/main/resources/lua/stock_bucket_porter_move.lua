-- The Porter bucket 库存搬运脚本。
--
-- 这个脚本只负责 Redis 层的原子搬运：从旧版本 bucket 扣除，再加到新版本 bucket。
-- MySQL bucket 的版本化调整由 Java 服务在同一业务流程中完成。
--
-- KEYS[1] = from stock key: ticket:stock:{ticketCategoryId}:v{fromVersion}:bucket:{fromBucketNo}
-- KEYS[2] = to stock key: ticket:stock:{ticketCategoryId}:v{toVersion}:bucket:{toBucketNo}
-- KEYS[3] = idempotency key for this move
-- KEYS[4] = target bucket soldout key
-- KEYS[5] = target version soldout key
--
-- ARGV[1] = quantity
-- ARGV[2] = idempotencyTtlSeconds
--
-- 返回码：
--  1  = SUCCESS，已完成搬运。
--  0  = SOURCE_NOT_ENOUGH，来源 bucket 可搬运库存不足。
-- -1  = DUPLICATE，幂等 key 已存在。
-- -2  = INVALID_ARGUMENT。
-- -3  = STOCK_VALUE_INVALID。
-- -4  = SOURCE_BUCKET_NOT_FOUND。
-- -5  = TARGET_BUCKET_NOT_FOUND。
local from_stock_key = KEYS[1]
local to_stock_key = KEYS[2]
local move_key = KEYS[3]
local target_bucket_soldout_key = KEYS[4]
local target_version_soldout_key = KEYS[5]

local quantity = tonumber(ARGV[1])
local idempotency_ttl_seconds = tonumber(ARGV[2])

if not quantity or quantity <= 0
        or not idempotency_ttl_seconds or idempotency_ttl_seconds <= 0 then
    return -2
end

if redis.call('EXISTS', move_key) == 1 then
    return -1
end

local from_value = redis.call('GET', from_stock_key)
if not from_value then
    return -4
end
local to_value = redis.call('GET', to_stock_key)
if not to_value then
    return -5
end

local from_stock = tonumber(from_value)
local to_stock = tonumber(to_value)
if not from_stock or not to_stock then
    return -3
end

if from_stock < quantity then
    return 0
end

redis.call('DECRBY', from_stock_key, tostring(quantity))
redis.call('INCRBY', to_stock_key, tostring(quantity))
redis.call('SET', move_key, tostring(quantity), 'EX', tostring(idempotency_ttl_seconds))
redis.call('DEL', target_bucket_soldout_key)
redis.call('DEL', target_version_soldout_key)
return 1
