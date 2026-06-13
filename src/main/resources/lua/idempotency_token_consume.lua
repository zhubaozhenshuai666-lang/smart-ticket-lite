if redis.call('EXISTS', KEYS[1]) == 1 then
    return redis.call('DEL', KEYS[1])
end

return 0
