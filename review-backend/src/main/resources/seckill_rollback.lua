-- 秒杀死信回滚脚本。
--
-- 使用场景：一条订单消息重试次数耗尽，且已经确认 MySQL 里没有对应订单，
-- 说明这次秒杀最终没有成交，必须把 Lua 入口扣掉的 Redis 状态还回去，否则库存会单向泄漏。
--
-- 为什么要写成 Lua：
-- 入口扣减（sadd + incrby -1 + xadd）是在一个脚本里原子完成的，回滚如果拆成三次往返，
-- 中间宕机会留下"一人一单记录已删、库存没还"的半回滚状态，那个用户可以再买一次而库存少一个。
-- 回滚必须和扣减保持同样的原子性。
--
-- 幂等性：
-- 只有当 srem 真的删掉了成员（返回 1）时才回补库存。
-- 这样即使这段脚本被重复执行，第二次 srem 返回 0，库存不会被多还一个。

local stockKey   = KEYS[1]   -- seckill:stock:{voucherId}
local orderKey   = KEYS[2]   -- seckill:order:{voucherId}
local streamKey  = KEYS[3]   -- stream.orders

local userId     = ARGV[1]
local group      = ARGV[2]
local messageId  = ARGV[3]

-- 1. 撤销一人一单标记。返回 1 表示本次确实删掉了，返回 0 表示之前已经删过。
local removed = redis.call('srem', orderKey, userId)

-- 2. 只在真的撤销了资格时才回补库存，保证脚本可重复执行。
if (removed == 1) then
    redis.call('incrby', stockKey, 1)
end

-- 3. 确认消息，把它从 pending-list 摘掉，队头不再被这条消息堵住。
--    ACK 放在最后：如果前面的回滚失败导致脚本中断，消息会留在 pending-list 等待下一轮，
--    而不会出现"已 ACK 但状态没回滚"的丢失。
redis.call('xack', streamKey, group, messageId)

return removed
