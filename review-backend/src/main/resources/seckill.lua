-- 秒杀资格校验 + MQ 投递脚本。
--
-- 技术重点：
-- 1. Redis 执行 Lua 脚本时是单线程串行执行的，脚本里的多条 Redis 命令具备原子性：
--    不会出现“刚判断库存充足，另一个请求马上把库存扣光”的竞态。
-- 2. 这里不直接写 MySQL，而是通过 XADD 写入 Redis Stream，让后台消费者异步落库：
--    入口请求只承接高并发资格判断，数据库写压力由 MQ 削峰。
-- 3. 返回码约定：
--    0 = 资格校验通过，消息已进入 stream.orders；
--    1 = Redis 库存不足；
--    2 = 用户已经买过，违反一人一单。

-- 1. 参数列表
-- 1.1. 优惠券 id
local voucherId = ARGV[1]
-- 1.2. 用户 id
local userId = ARGV[2]

-- 1.3. 订单 id
-- 订单 id 必须由 Java 入口层提前生成并传进来。
-- 这样接口返回给前端的 id 和 MQ 消费者最终落库的 id 是同一个，避免“返回 id”和“真实订单 id”不一致。
local orderId = ARGV[3]

-- 2. 数据 key
-- 2.1. Redis 秒杀库存 key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2. 已下单用户集合 key，用 set 做一人一单判断
local orderKey = 'seckill:order:' .. voucherId

-- 3. 脚本业务
-- 3.1. 判断库存是否充足。
-- 如果库存 key 还没预热，GET 会返回 nil；这里按 0 处理，直接拒绝，避免 tonumber(nil) 抛脚本异常。
local stock = tonumber(redis.call('get', stockKey) or '0')
if(stock <= 0) then
    -- 库存不足，返回 1
    return 1
end

-- 3.2. 判断用户是否已经下单。
-- SISMEMBER 是 O(1) 判断；高并发入口先在 Redis 拦住重复请求，减少数据库唯一性查询压力。
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 存在，说明是重复下单，返回 2
    return 2
end

-- 3.3. 扣 Redis 预库存。
-- 注意：这是入口层的“削峰库存”，真正 MySQL 库存仍会在消费者落库时用 stock > 0 再兜底一次。
redis.call('incrby', stockKey, -1)

-- 3.4. 记录用户已经参与该券秒杀。
-- 这一步必须和扣库存、写 MQ 在同一个 Lua 脚本里，否则中间失败会造成重复下单或库存不一致。
redis.call('sadd', orderKey, userId)

-- 3.5. 发送订单消息到 Redis Stream。
-- XADD stream.orders * k1 v1 ...:
-- - stream.orders 是队列名；
-- - * 代表 Redis 自动生成全局递增消息 id；
-- - 后面的 userId / voucherId / id 是消费者落库所需的最小字段。
--
-- 这一步成功后，Java 请求线程就可以返回 orderId；
-- 后台消费者通过 XREADGROUP 拉取消息、落库、XACK。
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
