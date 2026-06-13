-- Redis bucket 预扣释放脚本。
--
-- 失败补偿必须还回原始 bucket。预扣脚本会把 ticketCategoryId:v{bucketVersion}:bucketNo:quantity 写入 deducted key，
-- 释放时校验这个记录，避免把一个 requestId 的库存释放到错误 bucket。
--
-- KEYS[1] = ticket:stock:{ticketCategoryId}:v{bucketVersion}:bucket:{bucketNo}
-- KEYS[2] = ticket:stock:deducted:{requestId}
-- KEYS[3] = ticket:stock:compensated:{requestId}
-- KEYS[4] = ticket:soldout:{ticketCategoryId}:v{bucketVersion}:bucket:{bucketNo}
-- KEYS[5] = ticket:soldout:{ticketCategoryId}:v{bucketVersion}
--
-- ARGV[1] = ticketCategoryId
-- ARGV[2] = bucketVersion，兼容旧值时可传空字符串
-- ARGV[3] = bucketNo
-- ARGV[4] = quantity
-- ARGV[5] = compensatedTtlSeconds
--
-- 返回码：
--  1  = SUCCESS，已释放原始 bucket。
--  0  = NOT_DEDUCTED，预扣记录不存在或与原始 bucket 不匹配。
-- -1  = ALREADY_COMPENSATED，已经释放过。
-- -2  = INVALID_QUANTITY。
-- -3  = INVALID_TTL。
local bucket_stock_key = KEYS[1]
local deducted_key = KEYS[2]
local compensated_key = KEYS[3]
local bucket_soldout_key = KEYS[4]
local category_soldout_key = KEYS[5]

local ticket_category_id = tostring(ARGV[1])
local bucket_version = tostring(ARGV[2])
local bucket_no = tostring(ARGV[3])
local quantity = tonumber(ARGV[4])
local compensated_ttl_seconds = tonumber(ARGV[5])

if redis.call('EXISTS', compensated_key) == 1 then
    return -1
end

if not quantity or quantity <= 0 then
    return -2
end

if not compensated_ttl_seconds or compensated_ttl_seconds <= 0 then
    return -3
end

local deducted_value = redis.call('GET', deducted_key)
if not deducted_value then
    return 0
end

local expected_value
if bucket_version == nil or bucket_version == '' then
    expected_value = ticket_category_id .. ':' .. bucket_no .. ':' .. tostring(quantity)
else
    expected_value = ticket_category_id .. ':v' .. bucket_version .. ':' .. bucket_no .. ':' .. tostring(quantity)
end
if deducted_value ~= expected_value then
    return 0
end

redis.call('INCRBY', bucket_stock_key, tostring(quantity))
redis.call('DEL', deducted_key)
redis.call('SET', compensated_key, tostring(quantity), 'EX', tostring(compensated_ttl_seconds))
redis.call('DEL', bucket_soldout_key)
redis.call('DEL', category_soldout_key)
return 1
