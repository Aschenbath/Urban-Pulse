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
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 优惠券秒杀下单服务。
 *
 * 设计主线：
 * 1. 请求线程只执行 Redis Lua 原子校验，并把订单消息写入 Redis Stream，尽快返回订单 ID。
 * 2. Lua 脚本把“库存判断 + 一人一单判断 + 扣 Redis 预库存 + 写 MQ”放在一个原子操作里完成。
 * 3. 后台消费者从 Stream 拉取消息，在数据库事务中扣 MySQL 库存并创建订单。
 * 4. 数据库层仍保留 stock > 0 条件，作为 Redis 预库存之外的最终一致性兜底。
 *
 * 这里用 Redis Stream 而不是本地 BlockingQueue，是因为 Stream 有消息 ID、消费者组、ACK、pending-list；
 * 应用重启或消费者异常后可以补偿重试，本地内存队列则容易丢消息，也不适合多实例部署。
 *
 * <h3>毒丸消息与死信处理</h3>
 * ACK 放在数据库事务之后，投递语义是 at-least-once：消息不会丢，但可能重复投递。
 * 由此引出一个必须处理的问题：如果某条消息是永久性失败（重试多少次都不会成功），
 * 它会一直留在 pending-list 里被反复重试，把消费者卡死，后面所有正常订单被饿死。
 *
 * 处理策略分三步，顺序不能换：
 * <ol>
 *   <li><b>数次数而不是判性质。</b> 数据库超时和数据非法抛出来都是 Exception，没有可靠信号能区分，
 *       而且“判性质”隐含了“能预先枚举所有失败模式”这个不成立的假设。
 *       所以改为读取 PEL 的投递计数，超过 {@link #MAX_DELIVERY_COUNT} 就停靠，不问原因。</li>
 *   <li><b>回滚前必须先确认数据库状态。</b> at-least-once 意味着一条消息进入重试，
 *       可能是数据库已经写成功、只是 ACK 之前宕机了。此时如果直接回滚 Redis 预扣库存和一人一单记录，
 *       就会变成“数据库里有订单，但 Redis 库存被还回去了”，那个用户能再买一次，这是真超卖。
 *       所以停靠之前先查订单是否已存在：存在就只 ACK，绝不回滚。</li>
 *   <li><b>丢弃之前必须留痕。</b> 直接 ACK 丢掉会把“队列卡住”换成“静默丢单”：
 *       用户拿到了订单号，数据库里却永远没有这笔订单，且无人知晓。
 *       所以先把消息连同失败原因写入 {@link #STREAM_ORDERS_DEAD_KEY}，再执行回滚。</li>
 * </ol>
 *
 * 值得注意的是，第 2 步的“回滚前置确认”和落库时的“幂等判重”是同一个查询
 * （{@link #orderExists(Long, Long)}），一个查询承担两个职责。
 */
@Slf4j
@Service
@SuppressWarnings("unchecked")
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String STREAM_ORDERS_KEY = "stream.orders";

    /** 死信流：停靠重试耗尽的订单消息，保留失败原因供人工或定时补偿。 */
    private static final String STREAM_ORDERS_DEAD_KEY = "stream.orders.dead";

    private static final String STREAM_ORDERS_GROUP = "g1";
    private static final String STREAM_ORDERS_CONSUMER = "c1";
    private static final Duration STREAM_BLOCK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 投递次数上限。达到这个次数仍未成功的消息进入死信流程。
     *
     * 这个数和 {@link #MIN_IDLE_BEFORE_RETRY} 是一对，单独说“重试 3 次”没有意义：
     * 真正起作用的是两者相乘得到的<b>容忍窗口</b>，它必须覆盖瞬时故障的恢复时长，
     * 否则一次正常的抖动就会把好消息错杀进死信并回滚库存。
     *
     * 窗口取值来自 2026-08-30 在演示环境（2 vCPU / MySQL 8.0.46 / buffer pool 128M）的实测：
     * <ul>
     *   <li>连接池排队：Hikari 池大小 10，并发打到 120（12 倍池大小）时全部成功，P99 927ms、最坏 1.03s。
     *       注意 Hikari 的 connectionTimeout=30s 是<b>放弃等待的上限</b>，不是恢复时长；
     *       真正的恢复时间等于某个在飞查询归还连接的时间，实测是亚秒级。</li>
     *   <li>Redis 往返：本机 loopback，297 次采样平均 0.10ms、最大 1ms，可忽略。</li>
     * </ul>
     * 取最坏值约 1s，留 3 倍余量 → 容忍窗口 3s。
     * 投递发生在 t=0 / t=M / t=2M，覆盖窗口为 2M，故 M ≥ 1.5s，取整为 2s，实际窗口 4s。
     */
    static final long MAX_DELIVERY_COUNT = 3;

    /**
     * 两次投递之间的最小间隔，作为 XCLAIM 的 minIdleTime 传入。
     *
     * 这个参数是重试节奏的下界。缺了它（传 Duration.ZERO）时，重试快慢完全取决于消费循环的扫描频率：
     * 高负载下扫描很密，三次投递可能在一秒内烧光，那时 MAX_DELIVERY_COUNT 就形同虚设，
     * 一次两秒的数据库抖动足以把一条正常订单打进死信。
     * 交给 Redis 侧按闲置时长过滤后，重试节奏与本地循环速度解耦。
     */
    private static final Duration MIN_IDLE_BEFORE_RETRY = Duration.ofSeconds(2);

    /** 单轮 pending 扫描处理的消息数上限，避免一次扫描长时间占用消费线程。 */
    private static final int PENDING_SCAN_BATCH = 16;

    /** 即使一直有新消息，也每隔这么多轮强制扫一次 pending，防止高负载下积压的失败消息被饿死。 */
    private static final int PENDING_SCAN_INTERVAL = 64;

    private static final long ERROR_BACKOFF_MILLIS = 200L;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    /** 死信回滚脚本：srem + incrby + xack 三个动作原子完成，见 seckill_rollback.lua。 */
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    private ExecutorService seckillOrderExecutor;

    /** 消费循环的运行开关，配合 {@link #shutdown()} 做优雅停机。 */
    private volatile boolean running = true;

    /**
     * 启动后先创建 Stream 消费者组，再启动后台消费者。
     *
     * XGROUP CREATE stream.orders g1 $ MKSTREAM：
     * - $ 表示只消费创建组之后的新消息，避免重启时扫历史消息。
     * - MKSTREAM 表示 stream 不存在时自动创建。
     * - BUSYGROUP 表示消费者组已存在，是重复启动时的正常情况。
     */
    @PostConstruct
    private void init() {
        initStreamConsumerGroup();
        seckillOrderExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "seckill-order-consumer");
            thread.setDaemon(true);
            return thread;
        });
        seckillOrderExecutor.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void shutdown() {
        running = false;
        if (seckillOrderExecutor == null) {
            return;
        }
        seckillOrderExecutor.shutdownNow();
        try {
            seckillOrderExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
     * 秒杀入口：生成订单 ID，然后让 Lua 脚本完成库存校验、一人一单校验、预扣库存和写 Stream。
     *
     * 订单 ID 只生成一次：同一个 orderId 既返回给前端，也写入 MQ 给后台消费者落库，避免前端拿到的 ID
     * 与最终数据库订单 ID 不一致。
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
        if (code == 3) {
            /*
             * 库存 key 缺失或值非法：券在 MySQL 里是有效的，只是 Redis 侧没预热成功。
             * 这是运维故障而不是业务结果，必须打 error 让它可被发现，
             * 不能和"已抢完"共用同一条静默的失败路径。
             *
             * 用户文案也要和"真卖光"区分：真卖完了应该让用户放弃，
             * 而这里修复之后他是能买到的，文案要引导他重试而不是劝退。
             */
            log.error("秒杀库存未预热或值非法，voucherId={}，请检查 Redis key seckill:stock:{}",
                    voucherId, voucherId);
            return Result.fail("活动准备中，请稍后再试");
        }
        if (code != 0) {
            return Result.fail("秒杀请求处理失败");
        }

        return Result.ok(orderId);
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            // 启动时先补一轮历史 pending：上次进程退出时未 ACK 的消息不会因为消费者组用 $ 创建而被漏掉。
            scanPendingOnce();

            int loops = 0;
            while (running) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_ORDERS_GROUP, STREAM_ORDERS_CONSUMER),
                            StreamReadOptions.empty().count(1).block(STREAM_BLOCK_TIMEOUT),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );

                    if (records == null || records.isEmpty()) {
                        // 队列空闲，顺带把积压的失败消息推进一轮。
                        scanPendingOnce();
                        continue;
                    }

                    handleRecord(records.get(0));
                } catch (Exception e) {
                    /*
                     * 这里只做退避后继续，不再像早期实现那样一出错就跳进无界的 pending 循环。
                     * 原因：一次 Redis 抖动和一条永久失败的消息，性质完全不同却会命中同一个 catch；
                     * 让主循环继续往前走，把失败消息交给有次数上限的 pending 扫描去收敛。
                     */
                    log.error("消费秒杀订单消息异常，本轮跳过", e);
                    sleepQuietly(ERROR_BACKOFF_MILLIS);
                } finally {
                    if (++loops % PENDING_SCAN_INTERVAL == 0) {
                        // 高负载下 read 一直有返回，永远走不到空闲分支，这里兜底触发扫描。
                        scanPendingOnce();
                    }
                }
            }
        }
    }

    /**
     * 扫描一轮 pending-list。
     *
     * 与早期实现的关键差别：这是<b>有界</b>的。
     * 每轮最多处理 {@link #PENDING_SCAN_BATCH} 条，单条失败就换下一条，不会在同一条消息上死转。
     * 队头被毒丸消息堵住的问题，是在这里解决的。
     */
    void scanPendingOnce() {
        PendingMessages pendingMessages;
        try {
            pendingMessages = stringRedisTemplate.opsForStream().pending(
                    STREAM_ORDERS_KEY,
                    Consumer.from(STREAM_ORDERS_GROUP, STREAM_ORDERS_CONSUMER),
                    Range.unbounded(),
                    PENDING_SCAN_BATCH
            );
        } catch (Exception e) {
            log.error("读取 pending-list 失败，等待下一轮", e);
            return;
        }

        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return;
        }

        for (PendingMessage pending : pendingMessages) {
            if (!running) {
                return;
            }
            handlePendingMessage(pending);
        }
    }

    /**
     * 处理单条 pending 消息：按投递次数决定重试还是停靠。
     *
     * 投递计数来自 Redis Stream 的 PEL 自身，通过 XPENDING 读取，不需要额外维护计数器。
     * 注意 XPENDING 与 XRANGE 都只是读取，不会增加投递计数；真正让计数递增的是 XCLAIM 重新投递。
     */
    private void handlePendingMessage(PendingMessage pending) {
        RecordId recordId = pending.getId();
        long delivered = pending.getTotalDeliveryCount();

        if (delivered >= MAX_DELIVERY_COUNT) {
            MapRecord<String, Object, Object> record = readRecordById(recordId);
            if (record == null) {
                // 消息体已被裁剪或删除，PEL 里剩下的是孤儿条目，直接确认掉即可。
                log.warn("pending 消息体已不存在，直接 ACK 清理，id={}", recordId);
                acknowledge(recordId);
                return;
            }
            deadLetter(record, delivered);
            return;
        }

        // XCLAIM 把消息重新投递给自己，投递计数 +1，下一轮才能据此判断是否耗尽。
        // minIdleTime 传 MIN_IDLE_BEFORE_RETRY 而不是零：距上次投递不足这个时长的消息，
        // Redis 直接不返回，重试节奏因此有了与本地循环速度无关的下界。
        List<MapRecord<String, Object, Object>> claimed;
        try {
            claimed = stringRedisTemplate.opsForStream().claim(
                    STREAM_ORDERS_KEY,
                    STREAM_ORDERS_GROUP,
                    STREAM_ORDERS_CONSUMER,
                    MIN_IDLE_BEFORE_RETRY,
                    recordId
            );
        } catch (Exception e) {
            log.error("重新认领 pending 消息失败，等待下一轮，id={}", recordId, e);
            return;
        }

        if (claimed == null || claimed.isEmpty()) {
            // 消息已被处理或删除，无需重试。
            return;
        }

        try {
            handleRecord(claimed.get(0));
        } catch (Exception e) {
            /*
             * 单条重试失败不影响本轮其它消息：直接返回，让 for 循环处理下一条。
             * 这正是“队头阻塞”被打破的地方。
             */
            log.warn("pending 消息重试失败，已投递 {} 次，等待下一轮，id={}", delivered + 1, recordId, e);
        }
    }

    /**
     * 死信流程：确认 -> 留痕 -> 回滚 -> ACK。
     *
     * 三步的顺序是这个方法的全部要点，任何一步提前都会引入新的正确性问题。
     */
    private void deadLetter(MapRecord<String, Object, Object> record, long delivered) {
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        if (userId == null || voucherId == null) {
            // 消息本身不合法，回滚没有目标，只能留痕后确认掉，否则它会永远占着队头。
            log.error("死信消息缺少必要字段，无法回滚，仅留痕后 ACK，id={}", record.getId());
            writeDeadLetter(record, voucherOrder, delivered, "MALFORMED_MESSAGE");
            acknowledge(record.getId());
            return;
        }

        /*
         * 第一步：回滚前置确认。
         *
         * at-least-once 语义下，消息重投可能是因为数据库事务已经提交、只是 ACK 之前宕机。
         * 如果不做这次确认就回滚 Redis，会造成“MySQL 有订单 + Redis 库存已归还”，
         * 那个用户可以再买一次，这才是真正的超卖。
         *
         * 确认本身失败时什么都不做：宁可让消息继续留在 pending-list，也不能在状态未知时回滚。
         */
        boolean persisted;
        try {
            persisted = orderExists(userId, voucherId);
        } catch (Exception e) {
            log.error("死信前置确认失败，本轮不处理，userId={}, voucherId={}", userId, voucherId, e);
            return;
        }

        if (persisted) {
            log.warn("死信消息对应订单已落库，只确认不回滚，userId={}, voucherId={}", userId, voucherId);
            acknowledge(record.getId());
            return;
        }

        /*
         * 第二步：先留痕再回滚。
         *
         * 留痕失败就直接返回、不 ACK，消息留在 pending-list 下一轮再来。
         * 这样最坏情况是重复写一条死信记录（死信流同样是 at-least-once），
         * 而不是订单凭空消失且无人知晓。
         */
        try {
            writeDeadLetter(record, voucherOrder, delivered, "MAX_DELIVERY_EXCEEDED");
        } catch (Exception e) {
            log.error("写入死信流失败，暂不回滚也不 ACK，等待下一轮，id={}", record.getId(), e);
            return;
        }

        /*
         * 第三步：回滚 Redis 侧状态并 ACK。
         * srem + incrby + xack 三个动作在一个 Lua 脚本里原子完成，避免半回滚状态。
         */
        rollbackAndAck(voucherId, userId, record.getId());
    }

    /** 把消息连同失败原因写入死信流，保留可追溯的现场。 */
    private void writeDeadLetter(MapRecord<String, Object, Object> record,
                                 VoucherOrder voucherOrder,
                                 long delivered,
                                 String reason) {
        Map<String, String> deadLetter = new LinkedHashMap<>();
        deadLetter.put("originalMessageId", String.valueOf(record.getId()));
        deadLetter.put("orderId", String.valueOf(voucherOrder.getId()));
        deadLetter.put("userId", String.valueOf(voucherOrder.getUserId()));
        deadLetter.put("voucherId", String.valueOf(voucherOrder.getVoucherId()));
        deadLetter.put("deliveryCount", String.valueOf(delivered));
        deadLetter.put("reason", reason);
        deadLetter.put("deadAt", LocalDateTime.now().toString());

        stringRedisTemplate.opsForStream().add(STREAM_ORDERS_DEAD_KEY, deadLetter);
        log.error("秒杀订单消息重试耗尽，已停靠死信流，reason={}, userId={}, voucherId={}, deliveryCount={}",
                reason, voucherOrder.getUserId(), voucherOrder.getVoucherId(), delivered);
    }

    /** 执行回滚脚本：撤销一人一单、回补预扣库存、确认消息。 */
    private void rollbackAndAck(Long voucherId, Long userId, RecordId recordId) {
        try {
            stringRedisTemplate.execute(
                    ROLLBACK_SCRIPT,
                    Arrays.asList(
                            "seckill:stock:" + voucherId,
                            "seckill:order:" + voucherId,
                            STREAM_ORDERS_KEY
                    ),
                    userId.toString(),
                    STREAM_ORDERS_GROUP,
                    recordId.getValue()
            );
        } catch (Exception e) {
            // 回滚失败不 ACK，消息留在 pending-list；死信流里已经有记录，不会静默丢失。
            log.error("死信回滚失败，消息保留在 pending-list，userId={}, voucherId={}", userId, voucherId, e);
        }
    }

    private MapRecord<String, Object, Object> readRecordById(RecordId recordId) {
        String id = recordId.getValue();
        List<MapRecord<String, Object, Object>> records =
                stringRedisTemplate.opsForStream().range(STREAM_ORDERS_KEY, Range.closed(id, id));
        return (records == null || records.isEmpty()) ? null : records.get(0);
    }

    private void acknowledge(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, STREAM_ORDERS_GROUP, recordId);
    }

    private void handleRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

        /*
         * ACK 必须放在数据库事务成功之后。
         * 如果先 ACK 再落库，一旦数据库写入失败或 JVM 宕机，Redis 会认为消息已处理完成，
         * 这条订单消息不会进入 pending-list，也就无法再自动补偿，最终可能丢单。
         */
        runInTransaction(voucherOrder);

        acknowledge(record.getId());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stream 消费线程不是用户请求线程，不能依赖 AopContext.currentProxy()。
     *
     * 使用 TransactionTemplate 显式包住落库逻辑：
     * - 比内部类直接调用 @Transactional 方法更可靠。
     * - 避免 Spring AOP 自调用导致事务注解失效。
     */
    private void runInTransaction(VoucherOrder voucherOrder) {
        if (transactionTemplate == null) {
            createVoucherOrder(voucherOrder);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> createVoucherOrder(voucherOrder));
    }

    /**
     * 判断某个用户是否已经买过这张券。
     *
     * 这个查询承担两个职责：
     * 1. 落库时的幂等判重，避免 at-least-once 重复投递造成一人多单。
     * 2. 死信停靠前的回滚前置确认，避免在数据库已写成功的情况下错误回滚 Redis 状态。
     *
     * 依赖 tb_voucher_order 上的 uk_user_voucher 唯一索引：没有这个索引它是全表扫描，
     * 数据量上来之后自己就会超时，上面两个职责会同时失效。
     */
    boolean orderExists(Long userId, Long voucherId) {
        return query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0;
    }

    /**
     * 真正执行数据库落库。
     *
     * Redis Lua 已经做过入口削峰，但数据库层仍需要最终兜底：
     * - 先查是否已有订单，保证重复消费消息时幂等。
     * - 扣库存时加 stock > 0 条件，避免 Redis 与 MySQL 数据短暂不一致时超卖。
     *
     * 唯一索引 uk_user_voucher 是并发下的最后一道防线：
     * 多消费者并发时上面的查询是 check-then-act，并不安全，此时 insert 会抛 DuplicateKeyException，
     * 异常向上传播会让整个事务回滚（包括已扣的库存），消息留在 pending-list 重试，
     * 重试时查询命中已有订单直接返回，最终收敛到正确状态。所以这里刻意不捕获该异常。
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        if (orderExists(userId, voucherId)) {
            /*
             * 重复消费时如果订单已经存在，说明目标状态已经达成，可以让外层 ACK。
             * 典型场景：第一次 save 成功但 ACK 前宕机，消息被 pending-list 重放。
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
             * 不在这里吞掉失败并 ACK。
             * MySQL 扣库存失败可能意味着数据库临时异常，或者 Redis 预库存与 MySQL 库存不一致；抛异常可以阻止 XACK，
             * 让消息留在 pending-list 等待补偿处理。
             */
            throw new IllegalStateException("数据库扣减库存失败，可能库存不足或 Redis/MySQL 库存不一致，voucherId=" + voucherId);
        }

        save(voucherOrder);
    }
}
