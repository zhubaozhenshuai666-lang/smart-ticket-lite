-- Redis库存预扣脚本。
--
-- 为什么不用 Java 先 GET 再 DECR？
-- 高并发下，多个请求可能同时读到同一个库存值，然后各自判断“库存充足”，最后一起扣减导致超卖。
-- Lua 在 Redis 内部一次执行完“去重判断、库存判断、扣减、写入预扣记录”，中间不会被其他命令插队。
--
-- KEYS[1] = ticket:stock:{ticketCategoryId}
--   当前票档在 Redis 中的可抢库存。
-- KEYS[2] = ticket:stock:deducted:{requestId}
--   当前异步请求的预扣标记，用于防止同一个 requestId 被重复扣库存。
-- KEYS[3] = ticket:soldout:{ticketCategoryId}
--   票档售罄快速失败标记。它只是性能优化，不是最终库存事实；真实库存仍以 Redis 预扣和 MySQL 条件扣减为准。
--
-- ARGV[1] = quantity
--   本次请求要购买的票数。
-- ARGV[2] = deductedRecordTtlSeconds
--   预扣标记的过期时间。它需要覆盖正常下单、MQ消费和补偿窗口，避免短时间重复请求重复扣减。
-- ARGV[3] = soldoutTtlSeconds
--   售罄标记过期时间。不能永久设置，避免补偿、人工调库存或异常恢复后长时间误杀正常请求。
--
-- 返回码：
--  1  = SUCCESS，扣减成功并写入 requestId 预扣记录。
--  0  = STOCK_NOT_ENOUGH，Redis可售库存不足。
-- -1  = STOCK_NOT_FOUND，库存未预热或 key 不存在。
-- -2  = DUPLICATE，同一个 requestId 已经预扣过，不允许重复扣。
-- -3  = INVALID_QUANTITY，购买数量或 soldout TTL 非法。
-- -5  = STOCK_VALUE_INVALID，库存 key 存在但不是整数，需要重新预热。
local stock_key = KEYS[1]
local deducted_key = KEYS[2]
local soldout_key = KEYS[3]
local quantity = tonumber(ARGV[1])
local deducted_record_ttl_seconds = tonumber(ARGV[2])
local soldout_ttl_seconds = tonumber(ARGV[3])

if not quantity or quantity <= 0 then
    return -3
end

if not soldout_ttl_seconds or soldout_ttl_seconds <= 0 then
    return -3
end

if redis.call('EXISTS', deducted_key) == 1 then
    return -2
end

local current = redis.call('GET', stock_key)
if not current then
    return -1
end

local stock = tonumber(current)
if not stock then
    return -5
end

if stock < quantity then
    redis.call('SET', soldout_key, '1', 'EX', tostring(soldout_ttl_seconds))
    return 0
end

redis.call('DECRBY', stock_key, tostring(quantity))
redis.call('SET', deducted_key, tostring(quantity), 'EX', tostring(deducted_record_ttl_seconds))
return 1
