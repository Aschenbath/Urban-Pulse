# History

## 2026-06-30

- Corrected the public repository scope to the full review-system project structure: Spring Boot backend under `review-backend/` plus local nginx frontend assets under `nginx-1.18.0/`.
- Reframed the repository README as a public project showcase: product overview, screenshots, feature list, architecture flow, local run commands, and verification commands.
- Kept the Redis Stream MQ seckill flow as the main technical capability: one generated order ID, Lua qualification check + `XADD`, async consumer group, ACK after DB transaction, and pending-list compensation.
