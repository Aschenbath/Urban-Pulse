# Redis Practice Backend

一个用于练习 Java 后端 Redis 能力的 Spring Boot 项目，重点覆盖登录态、缓存、分布式锁、秒杀库存、Lua 原子脚本和 Redis Stream 异步下单。

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

## 功能模块

- 用户登录：验证码登录、Redis Hash token 存储、登录态 TTL 刷新。
- 商铺缓存：空值缓存防穿透、互斥锁重建缓存、逻辑过期模型。
- 秒杀下单：Lua 原子判断库存和一人一单，Redis Stream 异步创建订单。
- 分布式锁：基于 `SET NX EX` 的自定义锁、Lua 原子释放锁、Redisson 配置示例。
- 全局 ID：基于 Redis 自增序列生成订单 ID。

## 秒杀链路

秒杀接口不会直接写 MySQL，而是先在 Redis 内完成高并发资格判断：

1. Java 入口生成一次 `orderId`。
2. `seckill.lua` 原子执行库存判断、一人一单判断、Redis 预库存扣减和 `XADD stream.orders`。
3. 接口立即返回同一个 `orderId`。
4. 后台消费者通过 Redis Stream consumer group `g1/c1` 拉取订单消息。
5. 数据库事务落库成功后才 `XACK`。
6. 如果消费者异常，消息保留在 pending-list，恢复后补偿处理。

这条链路的核心目的是把用户请求和数据库写入解耦，用 Redis 承接入口并发，用 MySQL 做最终一致性兜底。

## 本地运行

### 1. 准备依赖

- JDK 17
- Maven 3.8+
- MySQL 8
- Redis 6+

### 2. 初始化数据库

创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS redis_practice DEFAULT CHARACTER SET utf8mb4;
```

导入 SQL：

```powershell
mysql -u root -p redis_practice < src/main/resources/db/redis_practice.sql
```

### 3. 配置环境变量

参考 `.env.example` 设置本地环境变量。常用配置：

```powershell
$env:MYSQL_PASSWORD='your_mysql_password'
$env:REDIS_PORT='6380'
```

也可以直接修改 `src/main/resources/application.yaml` 中的默认值。

### 4. 启动

```powershell
mvn spring-boot:run
```

默认后端端口是 `8081`。

## 验证

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
└── utils         # Redis 工具、锁、ID、常量
```

## 面试可讲点

- Redis token 登录为什么比本机 Session 更容易横向扩展。
- 缓存穿透和缓存击穿的处理差异。
- Lua 为什么能保证多条 Redis 命令的原子性。
- Redis Stream 的 consumer group、`XACK` 和 pending-list 如何保证异步链路不轻易丢单。
- 为什么数据库层仍需要 `stock > 0` 条件更新兜底。
