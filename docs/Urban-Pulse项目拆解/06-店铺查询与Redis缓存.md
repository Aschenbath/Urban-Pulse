# 06. 店铺查询与 Redis 缓存

## 本章目标

这一章是 Redis 面试高频区。要分清：

- 当前真正启用的是哪一种缓存策略。
- 穿透、击穿、雪崩分别是什么。
- `CacheClient` 中备选实现存在哪些真实 bug。

## 从直接查数据库开始

最初版本可以只有：

```java
Shop shop = getById(id);
if (shop == null) {
    return Result.fail("店铺不存在！");
}
return Result.ok(shop);
```

问题是店铺详情属于高频读、低频写。如果每次都查 MySQL，大量重复请求会浪费数据库资源。

## 当前实际调用

`ShopServiceImpl`：

```java
Shop shop = cacheClient.queryWithPassThrough(
        CACHE_SHOP_KEY,
        id,
        Shop.class,
        this::getById,
        CACHE_SHOP_TTL,
        TimeUnit.MINUTES
);
```

参数含义：

| 参数 | 值 | 含义 |
| --- | --- | --- |
| keyPrefix | `cache:shop:` | Redis Key 前缀 |
| id | 店铺 ID | 拼成最终 Key |
| type | `Shop.class` | JSON 反序列化目标 |
| dbFallback | `this::getById` | 缓存未命中时查询数据库 |
| time | 30 | 正常缓存时间 |
| unit | MINUTES | TTL 单位 |

最终 Key 示例：`cache:shop:1`。

## CacheClient.set

```java
public void set(String key, Object value,
                Long time, TimeUnit unit) {
    stringRedisTemplate.opsForValue().set(
            key,
            JSONUtil.toJsonStr(value),
            time,
            unit
    );
}
```

它统一把 Java 对象转成 JSON String 存入 Redis。

## 当前启用：缓存穿透保护

完整逻辑：

```java
public <R, ID> R queryWithPassThrough(
        String keyPrefix,
        ID id,
        Class<R> type,
        Function<ID, R> dbFallback,
        Long time,
        TimeUnit unit) {

    String key = keyPrefix + id;
    String json = stringRedisTemplate.opsForValue().get(key);

    if (StrUtil.isNotBlank(json)) {
        return JSONUtil.toBean(json, type);
    }

    if (json != null) {
        return null;
    }

    R r = dbFallback.apply(id);
    if (r == null) {
        stringRedisTemplate.opsForValue().set(
                key, "", CACHE_NULL_TTL, TimeUnit.MINUTES
        );
        return null;
    }

    this.set(key, r, time, unit);
    return r;
}
```

### 第一种状态：正常命中

```java
if (StrUtil.isNotBlank(json)) {
    return JSONUtil.toBean(json, type);
}
```

Redis 中是正常 JSON，直接转换成 Shop，不查数据库。

### 第二种状态：命中空值

```java
if (json != null) {
    return null;
}
```

这里很容易看不懂：

- `json == null`：Redis 中没有这个 Key。
- `json.equals("")`：Redis 有 Key，但值是空字符串。

前面 `isNotBlank` 已排除正常 JSON；此处 `json != null` 就表示命中了专门缓存的空值。

### 第三种状态：真正未命中

```java
R r = dbFallback.apply(id);
```

`dbFallback` 是 `this::getById`，这里才查询 MySQL。

### 数据库也不存在

```java
stringRedisTemplate.opsForValue().set(
        key, "", CACHE_NULL_TTL, TimeUnit.MINUTES
);
```

把空字符串缓存 2 分钟。攻击者持续查询不存在的 ID 时，大部分请求不会反复穿透到数据库。

### 数据存在

```java
this.set(key, r, time, unit);
```

写入 30 分钟正常 JSON。

## 缓存穿透、击穿、雪崩

### 穿透

查询数据库不存在的数据，缓存永远无法保存正常对象，请求持续打到数据库。

项目当前方案：短 TTL 空值。

其他方案：布隆过滤器、参数合法性校验。

### 击穿

一个超热点 Key 失效瞬间，大量请求同时查数据库。

仓库备选：互斥锁、逻辑过期。当前店铺调用没有启用。

### 雪崩

大量 Key 同一时间过期或 Redis 整体不可用，数据库瞬间承受全部压力。

当前项目没有系统性雪崩保护。推荐随机 TTL、热点预热、多级缓存、限流、熔断和 Redis 高可用。

## 更新店铺：Cache Aside

```java
@Transactional(rollbackFor = Exception.class)
public Result updateShop(Shop shop) {
    Long id = shop.getId();
    if (id == null) {
        return Result.fail("店铺id不能为空");
    }

    updateById(shop);
    stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    return Result.ok();
}
```

顺序是：

```text
更新 MySQL
→ 删除 Redis
→ 下次查询重新回填
```

### 为什么不直接更新缓存

同时更新数据库和缓存需要维护两份对象转换，且冷门数据可能更新后长期没人读，白白占缓存。删除更简单，下一次读取时按数据库真值重建。

### 一致性窗口

在数据库更新和缓存删除之间，其他线程可能读到旧缓存。删除失败也会保留旧数据。生产系统可以结合重试、消息队列、延迟双删或 CDC，但没有一种通用方案能让 Redis 和 MySQL 自动进入同一事务。

当前代码还没有检查 `updateById(shop)` 的返回值。

## 仓库备选一：互斥锁重建

入口逻辑：

```java
String shopJson = stringRedisTemplate.opsForValue().get(key);
if (StrUtil.isNotBlank(shopJson)) {
    return JSONUtil.toBean(shopJson, type);
}
if (shopJson != null) {
    return null;
}
```

与穿透方案相同，先区分正常值、空值和不存在。

尝试加锁：

```java
boolean isLock = tryLock(lockKey);
if (!isLock) {
    Thread.sleep(50);
    return queryWithMutex(
            keyPrefix, id, type, dbFallback, time, unit
    );
}
```

抢不到锁就等待后重试，希望拿锁线程已经把缓存重建好。

拿锁后双检：

```java
String json2 = stringRedisTemplate.opsForValue().get(key);
if (StrUtil.isNotBlank(json2)) {
    return JSONUtil.toBean(json2, type);
}
```

如果等待期间其他线程已经回填，就不重复查库。

最后查数据库、写缓存并释放锁。

### 当前实现的严重问题

方法结构是：

```java
try {
    boolean isLock = tryLock(lockKey);
    if (!isLock) {
        Thread.sleep(50);
        return queryWithMutex(...);
    }
    // rebuild
} finally {
    unlock(lockKey);
}
```

抢锁失败的线程也会在递归调用返回后执行 `finally`，从而删除一个自己没有持有的锁。并且 `unlock` 只是直接 DEL，没有校验 owner。

因此这段不能作为可靠的互斥锁实现使用。

另外，`ShopServiceImpl` 注释示例写成了不存在的 `setWithMutex`，真实方法叫 `queryWithMutex`。由于代码被注释，编译器不会发现这个错误。

## 仓库备选二：逻辑过期

写缓存时不设置物理 TTL，而是把过期时间放进值：

```java
RedisData redisData = new RedisData();
redisData.setData(value);
redisData.setExpireTime(
        LocalDateTime.now().plusSeconds(unit.toSeconds(time))
);
stringRedisTemplate.opsForValue().set(
        key, JSONUtil.toJsonStr(redisData)
);
```

Redis 实际保存：

```json
{
  "expireTime": "2026-07-13T10:00:00",
  "data": { "id": 1, "name": "Cafe" }
}
```

读取：

```java
RedisData redisData = JSONUtil.toBean(json, RedisData.class);
R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
    return r;
}
```

没过期直接返回。过期时理想设计是：返回旧值，同时只有一个线程异步重建。

### 当前实现为什么实际上难以重建

获得锁后：

```java
String json2 = stringRedisTemplate.opsForValue().get(key);
if (StrUtil.isNotBlank(json2)) {
    return JSONUtil.toBean(json2, type);
}
```

逻辑过期 Key 本来就不会物理消失，所以 `json2` 几乎必然非空。代码直接 return，导致：

1. 不会提交异步重建任务。
2. 把包含 `expireTime/data` 的包装 JSON 当成 Shop 解析，类型错误。
3. return 发生在提交任务之前，也没有释放刚获得的锁，只能等 10 秒 TTL。

正确双检应重新解析 `RedisData` 并判断新的逻辑过期时间；如果其他线程已经刷新，返回其中的 data 并释放锁，否则提交重建。

所以当前逻辑过期代码只能作为需要修复的练习，不能声称已可靠解决缓存击穿。

## CacheClient 内部简单锁

```java
private boolean tryLock(String key) {
    Boolean flag = stringRedisTemplate.opsForValue()
            .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}

private void unlock(String key) {
    stringRedisTemplate.delete(key);
}
```

SETNX 和 TTL 在同一个命令中，避免“拿到锁但没来得及设置过期时间”的死锁。但 value 固定为 `1`，解锁不校验持有人：

```text
线程 A 获锁
→ A 执行超过 10 秒，锁过期
→ 线程 B 获得新锁
→ A 执行结束 DEL
→ A 误删 B 的锁
```

`SimpleRedisLock + unlock.lua` 解决了 owner 检查，Redisson 还能提供可重入和 watchdog。详见第 11 章。

## 店铺类型 ZSet 缓存

```java
Set<String> range = stringRedisTemplate.opsForZSet()
        .range(CACHE_SHOPTYPE_KEY, 0, -1);
```

未命中时：

```java
List<ShopType> shopTypes = query()
        .orderByAsc("sort").list();

for (ShopType shopType : shopTypes) {
    stringRedisTemplate.opsForZSet().add(
            CACHE_SHOPTYPE_KEY,
            JSONUtil.toJsonStr(shopType),
            shopType.getSort()
    );
}
```

ZSet 的 member 是 ShopType JSON，score 是 sort，所以 Redis 已按 score 排序。读取后再 Java `sort` 是重复操作。

当前 Key 没有 TTL，也没有更新/删除入口，数据库分类变化后缓存可能永久陈旧。

## 面试 30 秒讲法

> 店铺详情采用 Cache Aside。查询先读 Redis，命中 JSON 直接返回；未命中查 MySQL并回填；数据库不存在时写短 TTL 空字符串，防止无效 ID 持续穿透。店铺更新先更新 MySQL，再删除缓存。仓库也保留互斥锁和逻辑过期练习，但当前调用只启用了空值缓存，而且两个备选实现还有锁归属和双检问题，我不会把它们包装成已上线能力。

## 高频追问

### 空值缓存有什么副作用？

短时间内数据库新插入同 ID 数据时，缓存仍返回不存在；还可能被大量随机 ID 占用内存，所以 TTL 要短并配合参数限制。

### 为什么不用永久缓存？

永久缓存难以自动清理，更新删除失败会长期脏读。逻辑过期可以物理永久，但必须有可靠重建和主动更新机制。

### 互斥锁和逻辑过期如何选择？

- 互斥锁：一致性较好，未命中请求需要等待。
- 逻辑过期：允许短暂旧数据，优先低延迟和可用性。

## 自测

1. `json == null` 和 `json.equals("")` 分别代表什么？
2. 当前真正启用的是哪一种策略？
3. 更新店铺为什么删除缓存而不是直接更新？
4. 互斥锁备选实现为什么会误删别人的锁？
5. 逻辑过期双检为什么使异步重建无法发生？
6. 店铺类型 ZSet 为什么可能永久陈旧？
