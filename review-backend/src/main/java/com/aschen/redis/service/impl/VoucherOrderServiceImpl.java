package com.aschen.redis.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aschen.redis.dto.Result;
import com.aschen.redis.entity.VoucherOrder;
import com.aschen.redis.mapper.VoucherOrderMapper;
import com.aschen.redis.service.ISeckillVoucherService;
import com.aschen.redis.service.IVoucherOrderService;
import com.aschen.redis.utils.RedisIdWorker;
import com.aschen.redis.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 优惠券秒杀下单服务。
 *
 * 设计主线：
 * 1. 入口线程只做资格判断和快速返回，不直接落库，避免用户请求被数据库写入拖慢。
 * 2. Redis Lua 脚本负责“库存判断 + 一人一单判断 + 扣 Redis 预库存 + 写 MQ”这一组原子操作。
 * 3. Redis Stream 在这里充当轻量 MQ：生产者是 Lua 的 XADD，消费者是后台线程 XREADGROUP。
 * 4. 真正的 MySQL 扣库存和订单入库由 MQ 消费者异步完成，数据库层仍保留 stock > 0 兜底。
 *
 * 为什么不使用本地 BlockingQueue：
 * - BlockingQueue 只存在于当前 JVM 内存，应用重启消息会丢。
 * - 多实例部署时，每个 JVM 都有自己的队列，无法天然协调。
 * - 没有 ACK / pending-list 语义，消费者处理到一半宕机时不方便补偿。
 *
 * 为什么这里先用 Redis Stream 而不是 RabbitMQ：
 * - 项目已经依赖 Redis，Stream 可以在不引入额外中间件的前提下讲清 MQ 核心概念。
 * - Stream 具备消息 ID、消费者组、ACK、pending-list，足够覆盖“异步削峰”和“失败补偿”的核心场景。
 * - 生产环境如果需要更强的路由、延迟、死信、管理能力，可以再迁移到 RabbitMQ / RocketMQ / Kafka。
 */
@Slf4j
@Service
@SuppressWarnings("unchecked")
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String STREAM_ORDERS_KEY = "stream.orders";
    private static final String STREAM_ORDERS_GROUP = "g1";
    private static final String STREAM_ORDERS_CONSUMER = "c1";
    private static final Duration STREAM_BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 服务启动后初始化消费者组，并启动后台消费者。
     *
     * XGROUP CREATE stream.orders g1 $ MKSTREAM:
     * - stream.orders：订单消息队列名。
     * - g1：消费者组名，同组内的多个消费者可以分摊消息。
     * - $：从当前最新消息之后开始消费，避免重启后把历史已处理消息全部扫描一遍。
     * - MKSTREAM：如果 stream 不存在则创建，避免第一次启动报错。
     *
     * 如果组已经存在，Redis 会返回 BUSYGROUP，这是幂等启动的正常情况，忽略即可。
     */
    @PostConstruct
    private void init() {
        initStreamConsumerGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void initStreamConsumerGroup() {
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    "CREATE".getBytes(StandardCharsets.UTF_8),
                    STREAM_ORDERS_KEY.getBytes(StandardCharsets.UTF_8),
                    STREAM_ORDERS_GROUP.getBytes(StandardCharsets.UTF_8),
                    "$".getBytes(StandardCharsets.UTF_8),
                    "MKSTREAM".getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw new IllegalStateException("Failed to initialize Redis Stream consumer group.", e);
            }
        }
    }

    /**
     * 秒杀入口：只做 Redis 原子校验 + 写入 MQ，然后快速返回订单 ID。
     *
     * 订单 ID 只生成一次：
     * - 同一个 orderId 传给 Lua，Lua 写入 Redis Stream。
     * - 接口也把同一个 orderId 返回给前端。
     *
     * 这样能避免“前端拿到的订单 ID”和“异步消费者最终落库的订单 ID”不一致。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        if (result == null) {
            return Result.fail("秒杀服务繁忙，请稍后重试");
        }

        int code = result.intValue();
        if (code == 1) {
            return Result.fail("库存不足");
        }
        if (code == 2) {
            return Result.fail("不能重复下单");
        }
        if (code != 0) {
            return Result.fail("秒杀请求处理失败");
        }

        return Result.ok(orderId);
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_ORDERS_GROUP, STREAM_ORDERS_CONSUMER),
                            StreamReadOptions.empty().count(1).block(STREAM_BLOCK_TIMEOUT),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );

                    if (records == null || records.isEmpty()) {
                        continue;
                    }

                    handleRecord(records.get(0));
                } catch (Exception e) {
                    log.error("处理秒杀订单 MQ 消息异常，准备扫描 pending-list 做补偿", e);
                    handlePendingList();
                }
            }
        }

        /**
         * pending-list 是 Redis Stream consumer group 的失败补偿机制。
         *
         * 正常流程：
         * 1. XREADGROUP 读到消息。
         * 2. 业务落库成功。
         * 3. XACK 确认消息。
         *
         * 异常场景：
         * - 如果第 1 步后 JVM 宕机或第 2 步抛异常，消息不会丢，而是留在 pending-list。
         * - 下次消费者恢复后，用 ReadOffset.from("0") 读取本消费者组未 ACK 的旧消息。
         * - 重试成功后再 ACK。
         *
         * 技术重点：MQ 不是“放进去就完事”，必须处理 ACK 和失败补偿，否则异步下单会丢单。
         */
        private void handlePendingList() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_ORDERS_GROUP, STREAM_ORDERS_CONSUMER),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.from("0"))
                    );

                    if (records == null || records.isEmpty()) {
                        break;
                    }

                    handleRecord(records.get(0));
                } catch (Exception e) {
                    log.error("处理 pending-list 秒杀订单异常，短暂休眠后继续重试", e);
                    sleepQuietly(20);
                }
            }
        }

        private void handleRecord(MapRecord<String, Object, Object> record) {
            Map<Object, Object> value = record.getValue();
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

            /*
             * ACK 的位置非常关键：必须先完成数据库事务，再确认消息。
             *
             * 如果先 ACK 再落库：
             * - 后面数据库写失败或 JVM 宕机，Redis 会认为消息已经处理完成。
             * - 这条订单消息不会进入 pending-list，也就无法自动补偿，最终可能丢单。
             *
             * 所以这里让 createVoucherOrder 抛出的异常自然冒泡到外层 catch：
             * - handleRecord 不会走到 acknowledge。
             * - 消息留在 pending-list。
             * - handlePendingList 后续会重新读取并重试。
             */
            runInTransaction(voucherOrder);

            stringRedisTemplate.opsForStream().acknowledge(
                    STREAM_ORDERS_KEY,
                    STREAM_ORDERS_GROUP,
                    record.getId()
            );
        }

        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * MQ 消费线程不是用户请求线程，不能再依赖 AopContext.currentProxy()。
     *
     * 这里使用 TransactionTemplate 显式包住数据库写入：
     * - 比在内部类里直接调用 @Transactional 方法更可靠。
     * - 避免 Spring AOP 自调用失效的问题。
     */
    private void runInTransaction(VoucherOrder voucherOrder) {
        if (transactionTemplate == null) {
            createVoucherOrder(voucherOrder);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> createVoucherOrder(voucherOrder));
    }

    /**
     * 真正落库的方法。
     *
     * Redis Lua 已经做过库存和一人一单判断，但数据库层仍然要兜底：
     * - 先查订单，避免重复落库。
     * - 扣库存时加 stock > 0 条件，避免极端情况下超卖。
     *
     * 这里采用“双保险”：
     * Redis 负责高并发入口削峰，MySQL 负责最终一致性兜底。
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            /*
             * 这里直接 return 并允许外层 ACK，是因为“订单已经存在”通常代表幂等成功。
             * 常见场景是消费者第一次 save 成功后，还没 ACK 就宕机，消息被 pending-list 重放。
             * 重放时发现订单已存在，说明目标状态已经达成，不应该无限重试。
             */
            log.warn("用户已经购买过该优惠券，userId={}, voucherId={}", userId, voucherId);
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            /*
             * 不要在这里吞掉失败并 ACK。
             *
             * Redis 入口已经扣过预库存并写入了 MQ，正常情况下 MySQL 这里也应该扣减成功。
             * 如果失败，说明数据库库存和 Redis 预库存可能不一致，或者数据库临时异常。
             * 抛出异常可以阻止外层 XACK，让消息留在 pending-list 中等待补偿处理。
             */
            throw new IllegalStateException("数据库扣减库存失败，可能库存不足或 Redis/MySQL 库存不一致，voucherId=" + voucherId);
        }

        save(voucherOrder);
    }
}
