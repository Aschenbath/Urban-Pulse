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
