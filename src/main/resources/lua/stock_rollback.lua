local current = redis.call('GET', KEYS[1])
if not current then
    return -1
end

local stock = tonumber(current)
local quantity = tonumber(ARGV[1])

if not stock then
    return -3
end

if not quantity or quantity <= 0 then
    return -4
end

return redis.call('INCRBY', KEYS[1], tostring(quantity))
