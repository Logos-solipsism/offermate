-- 令牌桶限流（原子执行）
-- KEYS[1] = 桶 key
-- ARGV[1] = capacity 桶容量
-- ARGV[2] = rate     每秒补充令牌数
-- ARGV[3] = now      当前毫秒时间戳
-- ARGV[4] = need     本次请求消耗令牌数
-- 返回 { 是否放行(1/0), 剩余令牌数, 桶剩余存活毫秒 }
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local need = tonumber(ARGV[4])

local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- 按时间差补充令牌，不允许超过桶容量
local delta = math.max(0, now - ts)
tokens = math.min(capacity, tokens + delta * rate / 1000)

local allowed = 0
if tokens >= need then
  tokens = tokens - need
  allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- 桶空后 rate 秒即可填满，再多留 1s 冗余
local ttl = math.ceil(capacity / rate * 1000) + 1000
redis.call('PEXPIRE', KEYS[1], ttl)

return { allowed, math.floor(tokens), ttl }
