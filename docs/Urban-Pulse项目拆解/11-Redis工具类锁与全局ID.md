# 11. Redis 工具类、分布式锁与全局 ID

## RedisIdWorker 解决什么问题

订单在进入 Stream 前就需要 ID，不能依赖 MySQL 自增。要求：

- 多实例不重复。
- 大体按时间递增。
- Long 范围内可用。
- 不必每次访问数据库。

## ID 结构

源码：

```java
private static final long BEGIN_TIMESTAMP = 1640995200L;
private static final int COUNT_BITS = 32;
```

高位保存从 2022-01-01 开始的时间差，低 32 位保存当天 Redis 自增序列。

公式：

$$
ID = (timestampDelta << 32)\;|\;dailySequence
$$

## 逐段拆解 nextId

```java
LocalDateTime now = LocalDateTime.now();
long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
long timestamp = nowSecond - BEGIN_TIMESTAMP;
```

取得当前时间并减去自定义起点。自定义起点减少高位占用，使 ID 可用更久。

### 当前时区问题

`LocalDateTime.now()` 取得机器本地时间，但 `toEpochSecond(ZoneOffset.UTC)` 又把它解释成 UTC。机器在 Asia/Shanghai 时，会比真实 UTC epoch 多约 8 小时。

它一般不会破坏唯一性和趋势递增，但时间含义不准确。推荐：

```java
long nowSecond = Instant.now().getEpochSecond();
```

### 按日期拆分计数器

```java
String date = now.format(
        DateTimeFormatter.ofPattern("yyyyMMdd")
);

Long count = stringRedisTemplate.opsForValue().increment(
        "icr:" + keyPrefix + ":" + date
);
```

例如：

```text
icr:order:20260713 → 1, 2, 3, ...
```

Redis INCR 单线程原子执行，多实例共享同一个 Key 时不会拿到相同 count。

`icr` 可能原意是 `incr`，但只要所有调用一致就不影响功能。

### 位运算组合

```java
return (timestamp << COUNT_BITS) | count;
```

假设：

```text
timestamp = 100
count = 5
```

高 32 位放 100，低 32 位放 5。按位 OR 把两段组合成一个 Long。

### 当前限制

- 每天计数 Key 没有 TTL，日期 Key 会持续累积。
- 没检查 count 是否超过 $2^{32}-1$。
- Redis 不可用时无法生成 ID。
- 系统时钟大幅回拨会破坏趋势递增。
- 没有提供 ID 反解和监控。

## 为什么不用 UUID

UUID 随机性好，但字符串较长、索引局部性差、作为 MySQL 聚簇主键会造成更多页分裂。Long 趋势递增 ID 更适合订单主键。

不能说 UUID 一定不可用；这是存储和可读性上的取舍。

## 自定义锁接口

```java
public interface Ilock {
    boolean tryLock(Long timeout);
    void unLock();
}
```

它只定义尝试加锁和释放锁。命名更规范应为 `ILock`、`unlock()`。

## SimpleRedisLock 的 Key 和 owner

```java
private static final String KEY_PREFIX = "lock:";
private static final String ID_PREFIX =
        UUID.randomUUID().toString(true) + "-";
```

锁 value：

```text
JVM UUID + threadId
```

只用 threadId 不够，因为不同 JVM 都可能有线程 1。

## 加锁

```java
public boolean tryLock(Long timeout) {
    String threadId = ID_PREFIX
            + Thread.currentThread().getId();
    String key = KEY_PREFIX + name;

    Boolean success = stringRedisTemplate.opsForValue()
            .setIfAbsent(
                    key,
                    threadId,
                    timeout,
                    TimeUnit.SECONDS
            );

    return BooleanUtil.isTrue(success);
}
```

对应：

```redis
SET lock:{name} {owner} NX EX {timeout}
```

SETNX 和过期时间在同一个 Redis 命令完成，避免加锁成功后进程宕机导致永不过期。

## 为什么释放锁必须用 Lua

错误写法：

```text
GET lock
→ 判断 owner
→ DEL lock
```

GET 和 DEL 之间锁可能过期并被别人获得，旧 owner 会误删新锁。

`unlock.lua`：

```lua
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
```

检查 owner 和删除在一次 Lua 执行中完成，不会被其他命令插入。

Java 调用：

```java
stringRedisTemplate.execute(
        UNLOCK_SCRIPE,
        Collections.singletonList(KEY_PREFIX + name),
        ID_PREFIX + Thread.currentThread().getId()
);
```

即使旧锁已过期、别人获得新锁，owner 不同就不会删除。

## SimpleRedisLock 的限制

- 不可重入：同一线程再次加同一锁会失败。
- 没有 watchdog：任务超过 timeout，锁会提前释放。
- 没有等待、重试、超时中断和公平性控制。
- 没有主从切换一致性保证。
- 当前核心秒杀 Stream 方案并没有使用它。
- `UNLOCK_SCRIPE` 拼写错误，不影响运行但降低可读性。

## CacheClient 锁与 SimpleRedisLock 的区别

`CacheClient` 内部锁：

```java
setIfAbsent(key, "1", 10, SECONDS)
delete(key)
```

没有 owner 校验，存在误删风险。

`SimpleRedisLock`：

```text
唯一 owner value
+ Lua compare-and-delete
```

安全性更高，但仍没有续期和可重入。

## Redisson 配置

```java
@Bean
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer().setAddress(
            "redis://" + redisHost + ":" + redisPort
    );
    return Redisson.create(config);
}
```

Redisson 的 `RLock` 提供：

- 可重入。
- watchdog 自动续期。
- Pub/Sub 等待唤醒。
- 更丰富的锁类型。

但当前只创建了 Bean，没有业务代码注入 `RedissonClient` 或调用 `getLock()`。

另外，`application.yaml` 支持 `spring.redis.password`，但 RedissonConfig 没有设置密码。Redis 启用认证时 StringRedisTemplate 可能能连接，RedissonClient 却会失败。

## RedisConstants 怎么读

常量不等于功能：

| 常量 | 当前状态 |
| --- | --- |
| `LOGIN_CODE_KEY` | 已使用 |
| `LOGIN_USER_KEY` | 已使用 |
| `CACHE_SHOP_KEY` | 已使用 |
| `CACHE_SHOPTYPE_KEY` | 已使用 |
| `LOCK_SHOP_KEY` | 缓存备选使用 |
| `SECKILL_STOCK_KEY` | 已使用 |
| `BLOG_LIKED_KEY` | 未使用 |
| `FEED_KEY` | 未使用 |
| `SHOP_GEO_KEY` | 未使用 |
| `USER_SIGN_KEY` | 未使用 |

面试时必须通过“谁调用它”判断功能，而不是看常量名猜实现。

## 面试讲法

> 订单 ID 使用 Redis 自增序列和时间戳组合，高位是从固定起点开始的秒级差值，低 32 位是按业务和日期拆分的 INCR 序列，因此多实例下不重复且趋势递增。项目还实现了 SET NX EX 的简单分布式锁，value 使用 JVM UUID 加线程 ID，解锁通过 Lua 原子比较 owner 后删除。不过秒杀主链路现在使用 Lua 和 Stream，不依赖该锁；Redisson 也只是配置了 Bean，并未实际进入核心业务。

## 自测

1. RedisIdWorker 的高低位分别是什么？
2. 为什么 INCR 可以多实例唯一？
3. 当前时间戳计算有什么时区问题？
4. 为什么锁 value 不能只用 threadId？
5. GET 后 DEL 为什么不安全？
6. SimpleRedisLock 和 Redisson 的能力差异是什么？
7. 常量存在为什么不等于功能实现？

