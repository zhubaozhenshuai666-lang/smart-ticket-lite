-- Redis预扣库存释放脚本。
--
-- 这个脚本处理“Redis已经扣了库存，但后续 MySQL 落库、消息发送或消费者创建订单失败”的补偿。
-- 如果释放逻辑在 Java 中先判断再 INCR，同一个失败请求被重复补偿时会把库存加多。
-- 所以这里把“是否已补偿、是否真的预扣过、回加库存、删除预扣标记、写补偿标记”放在一次 Lua 执行里。
--
-- KEYS[1] = ticket:stock:{ticketCategoryId}
--   当前票档在 Redis 中的可抢库存。
-- KEYS[2] = ticket:stock:deducted:{requestId}
--   预扣标记。只有它存在，说明这个 requestId 真的扣过 Redis 库存。
-- KEYS[3] = ticket:stock:compensated:{requestId}
--   补偿标记。写入后可防止重复释放导致 Redis 库存多加。
--
-- ARGV[1] = quantity
--   需要释放回 Redis 的票数。
-- ARGV[2] = compensatedTtlSeconds
--   补偿标记保留时间，通常比下单处理窗口更长。
--
-- 返回码：
--  1  = SUCCESS，库存已释放，预扣标记已删除，补偿标记已写入。
--  0  = NOT_DEDUCTED，没有预扣标记，不能凭空补偿。
-- -1  = ALREADY_COMPENSATED，这个 requestId 已经补偿过。
-- -2  = INVALID_QUANTITY，释放数量非法。
-- -3  = INVALID_TTL，补偿标记TTL非法。
local stock_key = KEYS[1]
local deducted_key = KEYS[2]
local compensated_key = KEYS[3]
local quantity = tonumber(ARGV[1])
local compensated_ttl_seconds = tonumber(ARGV[2])

if redis.call('EXISTS', compensated_key) == 1 then
    return -1
end

if redis.call('EXISTS', deducted_key) == 0 then
    return 0
end

if not quantity or quantity <= 0 then
    return -2
end

if not compensated_ttl_seconds or compensated_ttl_seconds <= 0 then
    return -3
end

redis.call('INCRBY', stock_key, tostring(quantity))
redis.call('DEL', deducted_key)
redis.call('SET', compensated_key, tostring(quantity), 'EX', tostring(compensated_ttl_seconds))
return 1
