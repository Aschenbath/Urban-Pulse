# Urban-Pulse Study Guide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a complete Chinese Markdown study guide that teaches Gilbert how Urban-Pulse's Java backend was logically built, how every important code path works, and how to present it honestly in interviews.

**Architecture:** Organize the guide by business module rather than source directory. Each chapter maps verified source files into a build order, runtime data flow, code-block explanation, Redis/MySQL semantics, known limitations, interview wording, and self-test questions; repetitive MyBatis-Plus boilerplate is indexed through representative examples.

**Tech Stack:** Markdown, Java 21, Spring Boot 2.7.3, Spring MVC, MyBatis-Plus, MySQL 8, Spring Data Redis, Redis Lua, Redis Stream, Redisson, JUnit 5, Mockito, nginx, Vue 2 integration context.

---

### Task 1: Build the verified source inventory

**Files:**
- Create: `docs/Urban-Pulse项目拆解/源码覆盖索引.md`
- Read: `review-backend/src/main/java/com/aschen/redis/**/*.java`
- Read: `review-backend/src/main/resources/**/*`
- Read: `review-backend/src/test/java/com/aschen/redis/**/*.java`
- Read: `nginx-1.18.0/conf/nginx.conf`
- Read: `nginx-1.18.0/html/review/js/common.js`

- [ ] **Step 1: Enumerate production, resource, test, and integration files**

Run:

```powershell
Get-ChildItem review-backend/src/main/java -Recurse -Filter *.java
Get-ChildItem review-backend/src/main/resources -Recurse -File
Get-ChildItem review-backend/src/test/java -Recurse -Filter *.java
```

Expected: every backend source file is visible before writing the coverage index.

- [ ] **Step 2: Extract class, endpoint, Redis, table, and test facts**

Run targeted `Select-String` scans for `@RequestMapping`, mapping annotations, `opsFor`, `STREAM`, `LOGIN_`, `CACHE_`, `FEED_`, `save`, `query`, `update`, `@Transactional`, and `@Test`. Record facts only after opening the matching source block.

- [ ] **Step 3: Write the coverage index**

The index must contain these columns:

```markdown
| Source file | Responsibility | Study chapter | Explanation mode |
| --- | --- | --- | --- |
```

Use `Detailed block analysis` for business/concurrency code and `Pattern/index explanation` for boilerplate entities, mappers, and interfaces.

- [ ] **Step 4: Verify all production Java files are indexed**

Run a PowerShell comparison between Java basenames and Markdown backtick references. Expected: zero unindexed production Java files.

- [ ] **Step 5: Commit the inventory**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/源码覆盖索引.md'
git commit -m 'docs: map Urban-Pulse backend sources'
```

### Task 2: Create the learning entry point and authorship boundary

**Files:**
- Create: `docs/Urban-Pulse项目拆解/README.md`
- Create: `docs/Urban-Pulse项目拆解/00-学习路线与项目边界.md`

- [ ] **Step 1: Write the guide index**

Include the 17 numbered chapters, recommended three-pass study order, estimated mastery checkpoints without time promises, and links to every chapter.

- [ ] **Step 2: Write the honest authorship boundary**

State explicitly:

```markdown
Urban-Pulse is presented as Gilbert's Java backend learning and implementation project. The bundled Vue/nginx frontend is a supporting demonstration client and is not claimed as independently authored frontend work.
```

Provide truthful answers for “Did you build the frontend?” and “Was this independently completed?”.

- [ ] **Step 3: Define completion criteria**

Require the learner to explain the overall architecture, login, cache, Feed, and seckill flows without reading; identify actual limitations; and distinguish implemented behavior from recommended production improvements.

- [ ] **Step 4: Check links and commit**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/README.md' 'docs/Urban-Pulse项目拆解/00-学习路线与项目边界.md'
git commit -m 'docs: add Urban-Pulse learning roadmap'
```

### Task 3: Explain the project overview, stack, and build order

**Files:**
- Create: `docs/Urban-Pulse项目拆解/01-项目全貌与技术栈.md`
- Create: `docs/Urban-Pulse项目拆解/02-从零搭建项目的编码顺序.md`
- Read: `review-backend/pom.xml`
- Read: `review-backend/src/main/resources/application.yaml`
- Read: `nginx-1.18.0/conf/nginx.conf`
- Read: `nginx-1.18.0/html/review/js/common.js`

- [ ] **Step 1: Document the runtime architecture**

Explain browser → nginx `:8080` → `/api` rewrite → Spring Boot `:8081` → Redis/MySQL. Include a compact Mermaid flow with short labels and a table describing each runtime component.

- [ ] **Step 2: Explain every declared dependency by project use**

Cover Spring Web, Spring Data Redis, AOP, Commons Pool, MyBatis-Plus, MySQL driver, Redisson, Hutool, Jackson, Lombok, JUnit, and Mockito. Distinguish dependencies actually used from dependencies merely available.

- [ ] **Step 3: Reconstruct a rational from-zero build sequence**

Use this order and explain why each stage unlocks the next:

```text
Project/config → SQL/entities → mapper/service/controller skeleton
→ unified result/errors → login context → shop CRUD/cache
→ blog/follow/feed → voucher stock → ID/Lua/Stream seckill
→ tests and operational documentation
```

- [ ] **Step 4: Document version inconsistencies**

Record that the current `pom.xml` targets Java 21 while the public README still says JDK 17. Do not silently resolve it in documentation.

- [ ] **Step 5: Commit the foundation chapters**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/01-项目全貌与技术栈.md' 'docs/Urban-Pulse项目拆解/02-从零搭建项目的编码顺序.md'
git commit -m 'docs: explain Urban-Pulse architecture and build order'
```

### Task 4: Explain the database model and Spring request layering

**Files:**
- Create: `docs/Urban-Pulse项目拆解/03-数据库设计与领域模型.md`
- Create: `docs/Urban-Pulse项目拆解/04-Spring-Boot分层与请求流程.md`
- Read: `review-backend/src/main/resources/db/review_platform.sql`
- Read: entity, mapper, service-interface, controller, config, DTO, and advice classes.

- [ ] **Step 1: Map every SQL table to its entity and business module**

For each table, explain primary key, important columns, relationships, and indexes visible in SQL. Identify missing constraints that matter, especially a possible `(user_id, voucher_id)` unique key for one-order-per-user.

- [ ] **Step 2: Explain representative entity and mapper code**

Use `Shop`, `VoucherOrder`, `ShopMapper`, and `VoucherMapper.xml` as detailed examples. Summarize equivalent boilerplate files in a mapping table.

- [ ] **Step 3: Trace a standard HTTP request**

Explain nginx → DispatcherServlet → interceptor → controller → service → mapper → database → `Result`. Cover dependency injection, MyBatis-Plus `ServiceImpl`, and global exception advice.

- [ ] **Step 4: Explain DTO boundaries**

Describe why `UserDTO` excludes sensitive user fields, why `Result` standardizes responses, and why `ScrollResult` differs from page-number pagination.

- [ ] **Step 5: Commit data and layering chapters**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/03-数据库设计与领域模型.md' 'docs/Urban-Pulse项目拆解/04-Spring-Boot分层与请求流程.md'
git commit -m 'docs: explain Urban-Pulse data model and request layers'
```

### Task 5: Deeply explain login and double-interceptor authentication

**Files:**
- Create: `docs/Urban-Pulse项目拆解/05-用户登录与双拦截器鉴权.md`
- Read: `UserController.java`, `UserServiceImpl.java`, `LoginFormDTO.java`, `UserDTO.java`, `UserHolder.java`, `RefreshTokenInterceptor.java`, `loginInterceptor.java`, `MvcConfig.java`, `RedisConstants.java`, and `common.js`.

- [ ] **Step 1: Write the from-zero implementation order**

Explain User/DTO → validation → verification code → login → token Hash → ThreadLocal → refresh interceptor → authorization interceptor → MVC ordering → frontend header.

- [ ] **Step 2: Break down verification-code and login methods by logical block**

For each block, show the real code and explain inputs, outputs, Redis keys, registration-on-first-login, UUID token generation, BeanUtil conversion, and TTL.

- [ ] **Step 3: Trace one authenticated request**

Show how `authorization` becomes `login:token:{token}`, a `UserDTO` in ThreadLocal, a refreshed TTL, and eventual cleanup in `afterCompletion`.

- [ ] **Step 4: Explain current defects honestly**

Cover `LOGIN_CODE_TTL = 120` used with `HOURS`, `LOGIN_USER_TTL = 36000` used with `MINUTES`, verification codes logged to output, naming/style issues, and security limitations of the demonstration flow.

- [ ] **Step 5: Add interview scripts and self-test questions**

Include 30-second and 2-minute versions plus questions about why two interceptors are used, why ThreadLocal must be cleared, and why DTO rather than the full entity is stored.

- [ ] **Step 6: Commit the authentication chapter**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/05-用户登录与双拦截器鉴权.md'
git commit -m 'docs: dissect Urban-Pulse authentication flow'
```

### Task 6: Deeply explain shop caching and consistency

**Files:**
- Create: `docs/Urban-Pulse项目拆解/06-店铺查询与Redis缓存.md`
- Read: `ShopController.java`, `ShopServiceImpl.java`, `CacheClient.java`, `RedisData.java`, `RedisConstants.java`, `SimpleRedisLock.java`, `Ilock.java`, and `unlock.lua`.

- [ ] **Step 1: Explain baseline DB query and Cache Aside evolution**

Start from direct `getById`, then add cache hit/miss, null caching, update-then-delete, mutex rebuilding, and logical expiration.

- [ ] **Step 2: Break down all three CacheClient strategies**

Explain pass-through, mutex, and logical expiration block by block, including lock keys, TTLs, retries, stale-data behavior, and asynchronous rebuild threads.

- [ ] **Step 3: Identify actual active behavior**

State that `ShopServiceImpl` currently activates pass-through; mutex and logical-expire calls are commented alternatives rather than simultaneous production behavior.

- [ ] **Step 4: Document defects and production improvements**

Cover the logical-expire double-check deserialization/lock-release problem, recursive retry risk, simple unlock ownership risk, cache consistency window, penetration/avalanche/ breakdown terminology, and possible Redisson use.

- [ ] **Step 5: Add data-flow examples and interview answers**

Use concrete keys such as `cache:shop:1`, `lock:shop:1`, empty-string values, and TTL changes.

- [ ] **Step 6: Commit the cache chapter**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/06-店铺查询与Redis缓存.md'
git commit -m 'docs: dissect Urban-Pulse cache strategies'
```

### Task 7: Explain blogs, likes, and scrolling pagination

**Files:**
- Create: `docs/Urban-Pulse项目拆解/07-探店笔记点赞与滚动分页.md`
- Read: blog controller/service/entity/mapper files, `ScrollResult.java`, `UserServiceImpl.java`, and blog-related Redis constants.

- [ ] **Step 1: Reconstruct the blog feature build order**

Explain CRUD first, then author enrichment, hot ranking, like toggling, liked-user lookup, and feed pagination dependencies.

- [ ] **Step 2: Break down blog query and enrichment logic**

Show how author information and current-user like state are attached to returned blog objects, noting N+1 query risks where present.

- [ ] **Step 3: Explain ZSet likes**

Document key shape, member, score, `ZADD`/`ZREM`, top users, database like-count synchronization, and consistency limitations.

- [ ] **Step 4: Explain scrolling pagination**

Demonstrate `max`, `offset`, duplicate-score handling, and why ordinary page numbers can skip or duplicate changing feed data.

- [ ] **Step 5: Add interview scripts, limitations, and self-test questions**

- [ ] **Step 6: Commit the blog chapter**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/07-探店笔记点赞与滚动分页.md'
git commit -m 'docs: explain Urban-Pulse blogs and likes'
```

### Task 8: Explain follow relationships and the Feed inbox

**Files:**
- Create: `docs/Urban-Pulse项目拆解/08-关注关系与Feed流.md`
- Read: follow controller/service/entity/mapper files, blog publishing and feed-query code, `RedisConstants.java`, and `ScrollResult.java`.

- [ ] **Step 1: Explain follow-table operations**

Cover follow/unfollow, relationship checks, Redis Set synchronization if present, and common-follow intersection.

- [ ] **Step 2: Explain push-model Feed construction**

Trace blog publication → query followers → `feed:{userId}` ZSet → blog ID member → timestamp score.

- [ ] **Step 3: Explain inbox consumption**

Trace reverse-range-by-score, minimum timestamp, offset calculation, batch blog lookup, and author/like enrichment.

- [ ] **Step 4: Compare push, pull, and hybrid Feed models**

State why this project uses a push model and what breaks down for celebrity-scale fan counts.

- [ ] **Step 5: Add interview answers and commit**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/08-关注关系与Feed流.md'
git commit -m 'docs: explain Urban-Pulse follow feed flow'
```

### Task 9: Explain voucher setup and stock preparation

**Files:**
- Create: `docs/Urban-Pulse项目拆解/09-优惠券业务与库存预热.md`
- Read: voucher and seckill-voucher controller/service/entity/mapper files, `VoucherMapper.xml`, SQL schema, and seckill-stock constants.

- [ ] **Step 1: Separate normal voucher and seckill voucher responsibilities**

Explain why base voucher data and seckill-only stock/time fields are modeled separately.

- [ ] **Step 2: Trace seckill-voucher creation**

Explain database persistence plus Redis stock initialization using `seckill:stock:{voucherId}`.

- [ ] **Step 3: Explain consistency assumptions**

Document what happens if Redis stock is absent, stale, or inconsistent with MySQL and how the later database condition acts as a safety net.

- [ ] **Step 4: Add interview scripts and commit**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/09-优惠券业务与库存预热.md'
git commit -m 'docs: explain Urban-Pulse voucher stock setup'
```

### Task 10: Deeply explain the Redis Stream seckill pipeline

**Files:**
- Create: `docs/Urban-Pulse项目拆解/10-Redis-Stream秒杀下单.md`
- Read: `VoucherOrderController.java`, `VoucherOrderServiceImpl.java`, `RedisIdWorker.java`, `seckill.lua`, voucher-order/seckill-voucher persistence files, SQL schema, and `VoucherOrderServiceImplTest.java`.

- [ ] **Step 1: Reconstruct the evolution from synchronous ordering**

Explain direct DB ordering → synchronized/distributed locking concerns → Lua admission control → Stream asynchronous persistence.

- [ ] **Step 2: Break down the request thread**

Explain `UserHolder`, single order-ID generation, Lua arguments and return codes, immediate response, and why the returned ID must equal the queued ID.

- [ ] **Step 3: Break down `seckill.lua` line by line by business block**

Explain stock lookup, nil handling, `SISMEMBER`, `INCRBY`, `SADD`, `XADD`, atomicity, and the absence of rollback after successful script execution.

- [ ] **Step 4: Break down Stream initialization and normal consumption**

Explain `XGROUP CREATE ... MKSTREAM`, consumer group, `XREADGROUP`, blocking reads, consumer identity, and application startup behavior.

- [ ] **Step 5: Break down transaction, ACK, and pending-list handling**

Explain `TransactionTemplate`, idempotent duplicate detection, conditional stock decrement, save, post-commit ACK, failure retention, and pending-list replay.

- [ ] **Step 6: Document failure windows and limitations**

Cover fixed group/consumer names, single local executor, poison-message infinite retries, Redis/MySQL divergence, missing DB unique constraint, returned-before-persisted semantics, and lack of order-status querying.

- [ ] **Step 7: Add a full interview narrative and follow-up answers**

Include why Stream instead of local queue, RabbitMQ trade-offs, why ACK follows transaction, how duplicate delivery is handled, and what “prevent overselling” actually means.

- [ ] **Step 8: Commit the seckill chapter**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/10-Redis-Stream秒杀下单.md'
git commit -m 'docs: dissect Urban-Pulse Redis Stream seckill'
```

### Task 11: Explain utilities, locks, IDs, configuration, and common components

**Files:**
- Create: `docs/Urban-Pulse项目拆解/11-Redis工具类锁与全局ID.md`
- Create: `docs/Urban-Pulse项目拆解/12-配置异常处理与通用组件.md`
- Read: all files in `utils/`, all files in `config/`, `application.yaml`, DTO files, and upload/comment ancillary modules.

- [ ] **Step 1: Explain RedisIdWorker mathematically**

Document timestamp delta, bit shift, daily increment key, and the composition $ID = (timestampDelta << COUNT\_BITS) | sequence$ with actual constants from source.

- [ ] **Step 2: Explain simple and Redisson lock paths**

Cover SETNX+TTL, ownership tokens, Lua unlock, reentrancy/watchdog differences, and which implementation the current business path actually uses.

- [ ] **Step 3: Explain constants, regex, password, UserHolder, and RedisData**

Group small utility classes by responsibility and state whether they are active, legacy, or support code.

- [ ] **Step 4: Explain application and MVC configuration**

Cover environment defaults, MyBatis pagination, Redisson client creation, exception response handling, dependency injection, and startup class behavior.

- [ ] **Step 5: Summarize ancillary CRUD modules**

Explain comments, uploads, shop types, user info, and other non-core modules without pretending they carry the same interview weight as cache/seckill.

- [ ] **Step 6: Commit utility and configuration chapters**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/11-Redis工具类锁与全局ID.md' 'docs/Urban-Pulse项目拆解/12-配置异常处理与通用组件.md'
git commit -m 'docs: explain Urban-Pulse utilities and configuration'
```

### Task 12: Explain testing, local operation, and verified problems

**Files:**
- Create: `docs/Urban-Pulse项目拆解/13-测试运行与本地调试.md`
- Create: `docs/Urban-Pulse项目拆解/14-代码问题与改进方案.md`
- Read: all current tests, README run commands, `.env.example`, Maven configuration, and every defect cited by earlier chapters.

- [ ] **Step 1: Explain the local runtime prerequisites and startup order**

Cover MySQL initialization, Redis port/defaults, environment variables, Spring Boot startup, nginx startup, and browser URL.

- [ ] **Step 2: Classify every current test**

Separate actual business unit tests, Spring smoke tests, Redis integration experiments, and Java-language experiments. Explain what each proves and does not prove.

- [ ] **Step 3: Run the existing Maven test baseline**

Run:

```powershell
$env:JAVA_HOME='D:\JAVA_TechTool\JDKs\oracle-24.0.1'
mvn clean test
```

Working directory: `review-backend`.

Expected from project history: 6 tests and zero failures. If fresh output differs, record the new output and reason instead of copying the historical claim.

- [ ] **Step 4: Build a severity-ranked problem table**

Include evidence, user impact, interview-safe wording, and recommended fix for TTL units, consumer identity, DB uniqueness, logical-expire behavior, lock ownership, tests, secrets/logging, and version/document drift.

- [ ] **Step 5: Separate implemented behavior from proposed improvements**

Every improvement must be labeled “recommended” rather than described as existing code.

- [ ] **Step 6: Commit testing and improvement chapters**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/13-测试运行与本地调试.md' 'docs/Urban-Pulse项目拆解/14-代码问题与改进方案.md'
git commit -m 'docs: document Urban-Pulse testing and limitations'
```

### Task 13: Write the interview scripts and self-test bank

**Files:**
- Create: `docs/Urban-Pulse项目拆解/15-简历写法与面试讲稿.md`
- Create: `docs/Urban-Pulse项目拆解/16-高频追问与自测题.md`

- [ ] **Step 1: Write truthful resume bullets**

Provide concise bullets for login, cache, Stream seckill, and tests. Exclude frontend authorship, fabricated scale, and unverified performance numbers.

- [ ] **Step 2: Write layered project introductions**

Create 30-second, 2-minute, and 5-minute versions. Each must state personal contribution and use the same verified architecture facts.

- [ ] **Step 3: Write module-specific interview answers**

Cover authentication, cache, Feed, global ID, Lua, Stream, transactions, ACK, idempotency, overselling, and known limitations.

- [ ] **Step 4: Write an active-recall question bank**

Group questions into foundation, source tracing, Redis, database/concurrency, failure scenarios, and honest project ownership. Include answers after collapsible-style separators or clearly separated answer sections so the learner can self-test first.

- [ ] **Step 5: Commit interview materials**

```powershell
git add -- 'docs/Urban-Pulse项目拆解/15-简历写法与面试讲稿.md' 'docs/Urban-Pulse项目拆解/16-高频追问与自测题.md'
git commit -m 'docs: add Urban-Pulse interview study materials'
```

### Task 14: Audit the complete guide and record completion

**Files:**
- Modify: `docs/Urban-Pulse项目拆解/README.md`
- Modify: `docs/Urban-Pulse项目拆解/源码覆盖索引.md`
- Modify: `history.md`

- [ ] **Step 1: Verify the expected file set**

Run:

```powershell
Get-ChildItem 'docs/Urban-Pulse项目拆解' -Filter *.md | Sort-Object Name
```

Expected: `README.md`, chapters `00` through `16`, and `源码覆盖索引.md`.

- [ ] **Step 2: Scan for incomplete content and broken Markdown structure**

Scan for `TODO`, `TBD`, empty headings, replacement characters, conflict markers, and unmatched triple-backtick fence counts. Mentions inside an explicit audit instruction do not count as incomplete content.

- [ ] **Step 3: Verify source coverage**

Compare all production Java filenames against the coverage index. Expected: zero missing files. Manually confirm Lua, YAML, SQL, mapper XML, nginx config, common.js, and test files are also indexed.

- [ ] **Step 4: Verify authorship and claim safety**

Search for phrases implying independent frontend or full-stack authorship, fabricated QPS/user counts, production deployment, or implemented improvements. Expected: no unsafe claims.

- [ ] **Step 5: Verify code baseline and Git diff**

Run the Maven command from Task 12 again if any source code changed externally during documentation work. Run `git diff --check` and review `git status -sb`; do not stage unrelated pre-existing source modifications.

- [ ] **Step 6: Update project history**

Append one timestamped entry stating that the modular backend study guide was completed, what it covers, the frontend authorship boundary, and the fresh Maven verification result.

- [ ] **Step 7: Commit and push the final documentation state**

```powershell
git add -- 'docs/Urban-Pulse项目拆解' 'history.md'
git commit -m 'docs: complete Urban-Pulse backend study guide'
git push
```

Expected: only the guide and intended history addition are included in the final commit; pre-existing source modifications remain untouched.
