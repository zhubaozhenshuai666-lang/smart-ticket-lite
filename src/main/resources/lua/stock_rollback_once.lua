local current = redis.call('GET', KEYS[1])
if redis.call('EXISTS', KEYS[2]) == 1 then
    return -2
end

if not current then
    return -1
end

local stock = tonumber(current)
local quantity = tonumber(ARGV[1])
local marker_ttl_seconds = tonumber(ARGV[2])

if not stock then
    return -3
end

if not quantity or quantity <= 0 then
    return -4
end

if not marker_ttl_seconds or marker_ttl_seconds <= 0 then
    return -5
end

local remaining = redis.call('INCRBY', KEYS[1], tostring(quantity))
redis.call('SET', KEYS[2], '1', 'EX', tostring(marker_ttl_seconds))
return remaining
