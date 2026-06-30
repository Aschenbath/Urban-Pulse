# 点评系统 Redis 后端

一个本地生活点评类 Java 后端项目，围绕用户登录、商铺查询、探店内容、优惠券秒杀和异步下单展开。项目重点不是页面展示，而是 Redis 在真实后端业务里的使用：登录态、缓存、分布式锁、全局 ID、Lua 原子脚本和 Redis Stream 消息队列。

## 技术栈

- Java 17
- Spring Boot 2.7.3
- Spring MVC
- MyBatis-Plus
- MySQL 8
- Redis / Lettuce / Redisson
- Lua
- Redis Stream
- Maven

## 业务模块

- 用户登录：验证码登录、Redis Hash token 存储、登录态 TTL 刷新。
- 商铺服务：商铺查询、商铺类型缓存、商铺信息更新后缓存删除。
- 探店内容：博客发布、点赞、滚动分页查询。
- 优惠券服务：普通券、秒杀券、库存初始化。
- 秒杀下单：Lua 原子校验、Redis Stream 异步落库、pending-list 补偿。

## Redis 能力点

| 场景 | Redis 用法 | 解决的问题 |
| --- | --- | --- |
| 登录态 | Hash + TTL | token 登录、用户上下文刷新 |
| 商铺缓存 | String + JSON + TTL | 减少数据库查询 |
| 缓存穿透 | 空值缓存 | 拦截不存在数据的重复查询 |
| 缓存击穿 | 互斥锁 / 逻辑过期 | 热点 key 失效时保护数据库 |
| 一人一单 | Set | 秒杀重复下单去重 |
| 秒杀库存 | String + Lua | 高并发库存原子扣减 |
| 异步下单 | Redis Stream | 削峰、异步落库、失败补偿 |
| 分布式锁 | SET NX EX / Redisson | 控制并发重建缓存和临界区 |

## 秒杀下单链路

秒杀接口不直接写 MySQL，而是把高并发入口和数据库写入拆开：

1. Java 入口生成一次 `orderId`。
2. `seckill.lua` 在 Redis 内原子完成库存判断、一人一单判断、预库存扣减和 `XADD stream.orders`。
3. 接口快速返回同一个 `orderId`。
4. 后台消费者通过 Redis Stream consumer group `g1/c1` 拉取订单消息。
5. 事务落库成功后才 `XACK`。
6. 如果消费者异常，消息保留在 pending-list，恢复后重新处理。

这条链路的设计重点是：Redis 承接入口并发和资格校验，MySQL 负责最终落库和 `stock > 0` 兜底。

## 本地运行

### 1. 准备依赖

- JDK 17
- Maven 3.8+
- MySQL 8
- Redis 6+

### 2. 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS review_platform DEFAULT CHARACTER SET utf8mb4;
```

```powershell
mysql -u root -p review_platform < src/main/resources/db/review_platform.sql
```

### 3. 配置环境变量

参考 `.env.example`。示例：

```powershell
$env:MYSQL_PASSWORD='your_mysql_password'
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='6380'
```

### 4. 启动后端

```powershell
mvn spring-boot:run
```

默认端口：`8081`。

## 验证命令

编译：

```powershell
mvn -DskipTests compile
```

运行秒杀订单 ID 一致性测试：

```powershell
mvn "-Dtest=com.aschen.redis.service.impl.VoucherOrderServiceImplTest" "-DforkCount=0" test
```

## 目录结构

```text
src/main/java/com/aschen/redis
├── config        # MVC、MyBatis、Redisson 配置
├── controller    # HTTP API
├── dto           # 请求/响应对象
├── entity        # 数据表实体
├── interceptor   # 登录态拦截器
├── mapper        # MyBatis-Plus Mapper
├── service       # 业务接口与实现
└── utils         # Redis 工具、分布式锁、ID 生成器、常量
```

## 面试可讲点

- Redis token 登录为什么比本机 Session 更适合横向扩展。
- 缓存穿透、缓存击穿、缓存更新策略分别怎么处理。
- Lua 为什么能保证多条 Redis 命令的原子性。
- Redis Stream 的 consumer group、`XACK` 和 pending-list 如何保证异步下单可靠性。
- 为什么 Redis 已扣预库存后，MySQL 仍要用 `stock > 0` 条件更新做兜底。
