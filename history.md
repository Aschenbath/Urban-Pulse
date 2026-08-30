# History

## 2026-06-30

- Corrected the public repository scope to the full review-system project structure: Spring Boot backend under `review-backend/` plus local nginx frontend assets under `nginx-1.18.0/`.
- Reframed the repository README as a public project showcase: product overview, screenshots, feature list, architecture flow, local run commands, and verification commands.
- Kept the Redis Stream MQ seckill flow as the main technical capability: one generated order ID, Lua qualification check + `XADD`, async consumer group, ACK after DB transaction, and pending-list compensation.
- Polished the public README showcase visuals: refreshed the mobile CSS, replaced category icons with a consistent SVG set, added neutral showcase photos, and added `tools/capture-readme-screenshots.mjs` for reproducible screenshots.
- Renamed the public positioning to a Dianping-like local life review app while keeping the Redis backend capability as the technical focus.

## 2026-07-05

- 19:07: Added readable comments/Javadocs for the Redis-heavy backend paths: seckill order service, `seckill.lua`, cache rebuild strategies, Redis ID worker, simple Redis lock, Java 21 Maven config, and smoke-test placeholders. Verification passed with `JAVA_HOME=D:\JAVA_TechTool\JDKs\oracle-24.0.1; mvn test` (6 tests, 0 failures). Local default `JAVA_HOME` still points to missing `D:\APP\JDKs\ms-17.0.18`, so Maven needs that env fix or per-command override.
- 19:10: Clean Maven verification initially failed because JDK 24 did not run Lombok annotation processing automatically; `pom.xml` now defines `lombok.version` and adds Lombok under `maven-compiler-plugin` `annotationProcessorPaths`. Final verification: `JAVA_HOME=D:\JAVA_TechTool\JDKs\oracle-24.0.1; mvn clean test` compiled 71 main sources / 5 test sources and passed 6 tests.

## 2026-07-09

- 21:55:57: Portfolio positioning decision: keep `Urban-Pulse` as Gilbert's self-built Java backend fundamentals project, focused on Spring Boot, Redis Stream seckill/order flow, cache/concurrency, tests, and explainable backend design. Build the future vibe-coding/AI-assisted delivery capability as a separate project so the two narratives do not dilute each other.

## 2026-07-13

- 08:53: Completed a guided architecture review of Urban-Pulse, mapping the nginx/Vue frontend, Spring Boot request layers, Redis-backed login state, shop cache strategies, and Redis Stream seckill pipeline to their concrete source files. No product code was changed.
- 08:58: Gilbert clarified authorship scope: the bundled Vue/nginx frontend was not written by him and must be described only as a supporting demonstration client. Resume and interview positioning should claim his Java backend work—Spring Boot business APIs, Redis login/cache/concurrency, and Redis Stream seckill/order processing—without presenting Urban-Pulse as an independently built full-stack application.
- 09:44: Completed `docs/Urban-Pulse项目拆解/`, a 19-file modular backend study guide covering architecture, from-zero coding order, all 71 production Java sources, SQL/domain modeling, login/interceptors, cache strategies, actual blog/follow feature boundaries, voucher setup, Redis Stream seckill, locks/IDs, configuration, testing, defects, interview scripts, and 60 active-recall questions. The guide explicitly treats Vue/nginx as a supporting client, separates implemented behavior from recommended improvements, and records the fresh Maven baseline: 71 main sources, 5 test sources, 6 tests, 0 failures, `BUILD SUCCESS` with Java 21 release on JDK 24.0.1.
- 17:02:20 +08:00: Refined the resume narrative from feature listing into honest problem-to-decision chains: synchronous seckill database contention led to Lua qualification plus Stream decoupling; transaction-before-ACK implies at-least-once plus idempotency; high-read/low-write shop access led to the active Cache Aside and short-lived null cache; shared login state and sliding expiry led to Redis token plus ordered dual interceptors. Resume wording must not invent a production incident or claim the currently inactive, flawed mutex/logic-expiry cache exercises as production-ready.

## 2026-07-14

- 08:47:17 +08:00: Compressed the Urban-Pulse resume entry to a one-line background plus four two-sentence highlights suitable for a one-page resume. Merged hotspot-cache trade-offs into cache governance and merged order-ID consistency with transaction/ACK reliability; deeper limitations remain interview material rather than resume prose.
- 10:04:20 +08:00: Added `docs/Urban-Pulse项目拆解/面试拷打复习/`, a four-topic source-backed interview review pack for message reliability, Cache Aside/null caching, mutex-vs-logical-expiry hot-key handling, and Redis-token dual-interceptor authentication. The documents distinguish current code, inactive exercises, and recommended fixes; they explicitly cover ACK/transaction failure matrices, the missing order unique index, cache deletion before transaction commit, mutex owner bugs, logical-expiry double-check failure, real TTL units, over-broad anonymous paths, and unfinished logout. Mechanical audit passed for 1,925 body lines: balanced fences, zero replacement characters, all relative links valid, and each topic contains source mapping, interview questions, and self-tests. Not committed because the referenced seckill/cache source files already have unresolved working-tree changes.

## 2026-07-17

- 18:19:53 +08:00: Added `面试拷打复习/00-Urban-Pulse项目术语场景词典.md`, a 136-term source-backed glossary covering architecture, login state, caching, MySQL, Lua, Redis Stream reliability, transactions, IDs, locks, content boundaries, and testing. It labels current implementation, inactive repository alternatives, and recommended improvements; both learning indexes now link it first. Audit passed: 1,097 lines, balanced fences, zero replacement characters, 30/30 relative links valid, and no missing term numbers.

- 2026-07-23 20:28:04 +08:00: Refined the root README and GitHub About positioning for Urban-Pulse. Corrected the documented build target to Java 21, identified Vue/Nginx as a supporting demo client, documented the current pending-list and cache-exercise boundaries, and added the full mvn clean test verification command. Verification: 71 Java main sources compiled with release 21 and Maven tests passed 6/6.

## 2026-08-29

- 17:20:00 +08:00: Deployed the Urban-Pulse demo to Bohrium `tzlz1496435.bohrium.tech` under `/opt/urban-pulse`, isolated from the sub2api and vault-desk stacks already on that host. Layout: Spring Boot on 127.0.0.1:8081, nginx static + `/api` proxy on 127.0.0.1:8082, MySQL 8 on 3306, a dedicated Redis on 6380 (never shares sub2api's 6379), and a cloudflared Quick Tunnel; all five run under supervisor as `urban-pulse-*`, and `urban-pulse-url` prints the current public address. Added `demo.expose-login-code`: with no SMS channel wired up, `/user/code` now returns the generated code in the response body and `login.html` fills the code input directly, so the deployed demo is actually loggable-in; set it to false once a real SMS provider is connected. `login.html` had to declare `form.phone` / `form.code` up front because Vue 2 cannot observe properties added later.
- 17:20:00 +08:00: Fixed a latent Java 21 packaging and startup failure found while producing the deployable jar. `spring-boot-starter-parent` 2.7.3 ships Spring Framework 5.3.22 and a `spring-boot-maven-plugin` whose bundled ASM cannot parse class file major version 65: `repackage` failed outright, and after pinning only the plugin the application still threw `Unsupported class file major version 65` during component scanning. Bumping the parent to 2.7.18 (Spring Framework 5.3.31) resolves both. Earlier sessions only ever ran `mvn test`, which never reaches repackage or context startup, so this never surfaced.
- 17:20:00 +08:00: Verified the deployment end to end against the public URL: shop-type/shop/blog APIs, static pages, code auto-fill (`{"success":true,"data":"156274"}`), login and token-authenticated `/user/me`, plus the headline seckill path. Seeded seckill voucher id 10 (stock 100, shop 1); one order returned id 631477904680681473, the repeat request was rejected with 不能重复下单, Redis stock went 100 to 99, `XPENDING stream.orders g1` returned 0 (ACK after the DB transaction), and `tb_voucher_order` / `tb_seckill_voucher` matched. Browser check on the real page confirmed the code lands in the input box and login redirects with a token. Added memory footprint roughly 415MB (app 285MB, MySQL 159MB tuned to a 128M buffer pool); host still reports about 1.9GB available and all 18 supervisor programs RUNNING.

## 2026-08-30

- 00:05 +08:00: 修复秒杀消费链路的毒丸消息队头阻塞。原实现里主循环任何异常都跳进 `handlePendingList()`，而后者是无界 `while(true)` + 20ms 重试，一条永久失败的消息会把消费者永久卡死，后面所有正常订单饿死。改造分三层：
  - **有界扫描替代无界循环**：`scanPendingOnce()` 每轮最多处理 16 条，单条失败即换下一条；主循环 catch 只做 200ms 退避后继续，不再钻进 pending 死循环。启动时先补扫一轮历史 pending（消费者组用 `$` 创建会漏掉上次未 ACK 的消息），空闲时扫，另每 64 轮强制扫一次防高负载饿死。
  - **数次数而非判性质**：放弃按异常类型区分永久/瞬时失败（数据库超时与数据非法都是 Exception，无可靠信号，且"能枚举所有失败模式"这个前提不成立）。改为读 PEL 的 `getTotalDeliveryCount()`，达到 `MAX_DELIVERY_COUNT=3` 即停靠。XPENDING/XRANGE 只读不增计数，靠 `XCLAIM`（`claim(key, group, owner, Duration.ZERO, recordId)`）重新投递来递增计数。
  - **回滚前置确认**：at-least-once 下消息重投可能是"事务已提交、ACK 前宕机"，此时直接回滚会造成 MySQL 有订单 + Redis 库存已归还 = 真超卖。所以停靠前先 `orderExists()`：存在则只 ACK 不回滚；不存在才先写死信流 `stream.orders.dead`（带 reason/deliveryCount/deadAt）再回滚。确认本身失败则什么都不做，宁可留在 pending。
  - 新增 `seckill_rollback.lua`：`srem` + `incrby` + `xack` 原子完成，且只在 `srem` 返回 1 时才回补库存，脚本可重复执行不会多还库存。ACK 放脚本最后，避免"已 ACK 未回滚"。
  - `tb_voucher_order` 加 `uk_user_voucher(user_id, voucher_id)` 唯一索引。这是整套逻辑的前提：没有它 `orderExists` 是全表扫描，会自己超时；同时它是多消费者并发下 check-then-act 的最终防线，`DuplicateKeyException` 刻意不捕获，让事务回滚后由重试收敛。
  - 关键设计点：`orderExists()` 一个查询承担两个职责，落库幂等判重 + 死信回滚前置确认。
  - 消费者线程改为具名 daemon + `@PreDestroy` 优雅停机，executor 从 static 改为实例字段。
  - 验证：`JAVA_HOME=D:\JAVA_TechTool\JDKs\oracle-24.0.1; mvn -B clean test` → Tests run 10, Failures 0, Errors 0, BUILD SUCCESS（原 6 个测试，新增 4 个覆盖死信三步顺序与阈值分支）。
  - 仍未做（与本次改动耦合，需成对处理）：消费者名硬编码 `c1` + 单线程消费，多实例部署会冲突；Stream 不裁剪；`seckill:order` 集合无 TTL；库存预热失败仍是静默失败。
