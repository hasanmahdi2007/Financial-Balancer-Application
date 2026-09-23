local tokens_key = KEYS[1]
local timestamp_key = KEYS[2]

local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local current_tokens = tonumber(redis.call('get', tokens_key) or capacity)
local last_updated = tonumber(redis.call('get', timestamp_key) or now)

local time_passed = math.max(0, now - last_updated)
local tokens_to_add = time_passed * rate
current_tokens = math.min(capacity, current_tokens + tokens_to_add)

if current_tokens >= requested then
    current_tokens = current_tokens - requested
    redis.call('set', tokens_key, current_tokens)
    redis.call('set', timestamp_key, now)
    return 1
else
    return 0
end