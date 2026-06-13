-- Redis bucket 库存预扣脚本。
--
-- 单票档单 Redis Key 在热点票档下会变成热 key；bucket 分片把一个票档拆成多个 Redis Key。
-- 这里仍然必须用 Lua：Java 侧如果先读某个 bucket 库存再扣减，多个并发请求会读到同一份旧值。
--
-- KEYS[1] = ticket:stock:deducted:{requestId}
-- KEYS[2] = ticket:soldout:{ticketCategoryId}:v{bucketVersion}
-- KEYS[3..2+probeCount] = ticket:stock:{ticketCategoryId}:v{bucketVersion}:bucket:{bucketNo}
-- KEYS[3+probeCount..2+probeCount*2] = ticket:soldout:{ticketCategoryId}:v{bucketVersion}:bucket:{bucketNo}
--
-- ARGV[1] = quantity
-- ARGV[2] = deductedRecordTtlSeconds
-- ARGV[3] = soldoutTtlSeconds
-- ARGV[4] = ticketCategoryId
-- ARGV[5] = bucketVersion
-- ARGV[6] = initialBucketNo
-- ARGV[7] = bucketCount
-- ARGV[8] = probeCount
--
-- 返回码：
--  2  = PROBE_MISS，小窗口内所有 bucket 都不足；不能代表全局售罄。
--  1  = SUCCESS，已扣某个 bucket，并在 deducted key 中记录 ticketCategoryId:v{bucketVersion}:bucketNo:quantity。
--  0  = STOCK_NOT_ENOUGH，全桶探测后所有 bucket 都不足。
-- -1  = STOCK_NOT_FOUND，没有任何可用库存 key。
-- -2  = DUPLICATE，requestId 已经预扣过。
-- -3  = INVALID_QUANTITY，数量或参数非法。
-- -4  = BUCKET_NOT_FOUND，bucketCount 与 KEYS 不匹配或 bucket 缺失。
-- -5  = STOCK_VALUE_INVALID，库存 key 不是整数。
local deducted_key = KEYS[1]
local version_soldout_key = KEYS[2]

local quantity = tonumber(ARGV[1])
local deducted_record_ttl_seconds = tonumber(ARGV[2])
local soldout_ttl_seconds = tonumber(ARGV[3])
local ticket_category_id = tostring(ARGV[4])
local bucket_version = tostring(ARGV[5])
local initial_bucket_no = tonumber(ARGV[6])
local bucket_count = tonumber(ARGV[7])
local probe_count = tonumber(ARGV[8])

if not quantity or quantity <= 0
        or not deducted_record_ttl_seconds or deducted_record_ttl_seconds <= 0
        or not soldout_ttl_seconds or soldout_ttl_seconds <= 0
        or not bucket_version or bucket_version == ''
        or not initial_bucket_no or initial_bucket_no < 0
        or not bucket_count or bucket_count <= 0
        or not probe_count or probe_count <= 0 then
    return -3
end

if redis.call('EXISTS', deducted_key) == 1 then
    return -2
end

local attempt_limit = math.min(bucket_count, probe_count)
if #KEYS < 2 + attempt_limit * 2 then
    return -4
end

for offset = 0, attempt_limit - 1 do
    local bucket_no = (initial_bucket_no + offset) % bucket_count
    local stock_key_index = 3 + offset
    local bucket_soldout_key_index = 3 + attempt_limit + offset
    local stock_key = KEYS[stock_key_index]
    local bucket_soldout_key = KEYS[bucket_soldout_key_index]

    local current = redis.call('GET', stock_key)
    if not current then
        return -4
    end
    local stock = tonumber(current)
    if not stock then
        return -5
    end
    if stock >= quantity then
        redis.call('DECRBY', stock_key, tostring(quantity))
        redis.call(
                'SET',
                deducted_key,
                ticket_category_id .. ':v' .. bucket_version .. ':' .. tostring(bucket_no) .. ':' .. tostring(quantity),
                'EX',
                tostring(deducted_record_ttl_seconds)
        )
        return 1
    end
    redis.call('SET', bucket_soldout_key, '1', 'EX', tostring(soldout_ttl_seconds))
end

if attempt_limit >= bucket_count then
    redis.call('SET', version_soldout_key, '1', 'EX', tostring(soldout_ttl_seconds))
    return 0
end

return 2
