-- 只释放自己持有的 Redis 锁。
--
-- KEYS[1] = lock key
-- ARGV[1] = lock token
--
-- 返回码：
--  1 = released
--  0 = token mismatch or lock missing
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
return 0
