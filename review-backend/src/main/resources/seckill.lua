-- 秒杀资格校验 + Redis Stream 投递脚本。
--
-- Redis 执行 Lua 脚本时是单线程串行执行的，所以这里的库存判断、一人一单判断、预扣库存、写入 MQ
-- 可以作为一个原子业务单元完成，避免高并发下出现“刚判断有库存，马上被其它请求扣光”的竞态。
--
-- 这里不直接写 MySQL，而是通过 XADD 写入 Redis Stream，让后台消费者异步落库：
-- 入口请求只承接高并发资格判断，数据库写压力由 MQ 削峰。
--
-- 返回码约定：
-- 0 = 校验通过，订单消息已写入 stream.orders
-- 1 = Redis 预库存不足
-- 2 = 用户已购买过该优惠券
-- 3 = 库存 key 缺失或值非法，说明这张券没有被正确预热

-- 1. 入参
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 订单 ID 由 Java 请求线程提前生成并传入。
-- 这样接口返回给前端的 ID 与后续消费者真正落库的订单 ID 是同一个值。
local orderId = ARGV[3]

-- 2. Redis key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 3. 判断库存。
--
-- 这里把"没预热"和"真卖光"拆成两个返回码，因为它们是完全不同的两种情况：
-- 前者是故障（运维漏了预热，或 key 被误删、Redis 从旧 RDB 快照恢复时丢了 key），
-- 此时 MySQL 里其实还躺着一批可用的券；后者才是正常的业务结果。
--
-- 原先两种都返回 1，代价是：运维在日志和监控上看不到自己漏了预热，
-- 用户也会看到"已抢完"从而永久放弃，而这批人本来在修复后是能买到的。
--
-- GET 在 key 不存在时返回 false，一次调用就能区分，不需要额外的 EXISTS 往返。
local raw = redis.call('get', stockKey)
if (raw == false) then
    return 3
end

-- 值被写坏成非数字时 tonumber 返回 nil。同样按"未正确预热"处理，
-- 否则 nil 参与下面的比较会直接抛 Lua 错误，脚本失败又变回一次静默故障。
local stock = tonumber(raw)
if (stock == nil) then
    return 3
end

if (stock <= 0) then
    return 1
end

-- 4. 判断一人一单。
-- 用 set 记录已参与该券秒杀的用户，先在 Redis 入口挡住重复请求，减少数据库唯一性校验压力。
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 5. Redis 预扣库存。
-- 真正的 MySQL 库存仍会在消费者落库时用 stock > 0 再兜底一次。
redis.call('incrby', stockKey, -1)

-- 6. 记录用户已参与。
-- 这一步必须和预扣库存、写 MQ 在同一个 Lua 脚本里，否则中间失败会造成重复下单或库存不一致。
redis.call('sadd', orderKey, userId)

-- 7. 写入 Redis Stream，交给后台消费者异步创建订单。
-- XADD stream.orders * k1 v1 ... 中：
-- - stream.orders 是队列名；
-- - * 表示 Redis 自动生成递增消息 ID；
-- - userId / voucherId / id 是消费者落库所需的最小字段。
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
