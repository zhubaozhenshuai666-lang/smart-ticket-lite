--[[
Redis 库存 CAS + Delta 修复脚本。

为什么不用 Java get 后再 INCRBY：
  正常售卖期间，Redis 库存会被高并发预扣不断修改。Java 先读 before，再根据 before 计算 delta，
  到执行 INCRBY 之间可能已经有新的下单请求扣减库存。裸 INCRBY 会把基于旧快照的修复量加到新库存上，
  造成二次偏差。

KEYS[1]:
  ticket:stock:{ticketCategoryId}，当前票档 Redis 可抢库存。

ARGV[1]:
  beforeRedisStock，业务层刚刚读取到的 Redis 库存快照。

ARGV[2]:
  delta = expectedRedisAvailable - beforeRedisStock。

返回码：
  1  = SUCCESS，当前 Redis 值仍等于 beforeRedisStock，已经安全 INCRBY delta。
 -1  = STOCK_NOT_FOUND，Redis key 不存在，当前脚本不做无保护 SET。
 -2  = CONCURRENT_MODIFIED，Redis 值已变化，说明有并发售卖或其他修复，本次放弃。
 -3  = STOCK_VALUE_INVALID，Redis 当前值不是整数。
]]

local stock_key = KEYS[1]
local expected_before = tonumber(ARGV[1])
local delta = tonumber(ARGV[2])

local current = redis.call('GET', stock_key)
if not current then
    return -1
end

local current_number = tonumber(current)
if not current_number or not expected_before or not delta then
    return -3
end

if current_number ~= expected_before then
    return -2
end

redis.call('INCRBY', stock_key, delta)
return 1
