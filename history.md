# History

## 2026-06-30

- Corrected the public repository scope to the full review-system project structure: Spring Boot backend under `review-backend/` plus local nginx frontend assets under `nginx-1.18.0/`.
- Preserved the local-life review / voucher seckill business identity for interview use, while removing public-facing course/source markers, course-style directory names, and local secrets.
- Kept the Redis Stream MQ seckill flow as the main interview highlight: one generated order ID, Lua qualification check + `XADD`, async consumer group, ACK after DB transaction, and pending-list compensation.
