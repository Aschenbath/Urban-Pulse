package com.aschen.redis.service.impl;

import com.aschen.redis.dto.Result;
import com.aschen.redis.dto.UserDTO;
import com.aschen.redis.utils.RedisIdWorker;
import com.aschen.redis.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class VoucherOrderServiceImplTest {

    private static final String STREAM_KEY = "stream.orders";
    private static final String DEAD_KEY = "stream.orders.dead";
    private static final String GROUP = "g1";
    private static final RecordId MESSAGE_ID = RecordId.of("1700000000000-0");

    @AfterEach
    void clearUserHolder() {
        UserHolder.removeUser();
    }

    @Test
    void seckillVoucherReturnsSameOrderIdThatLuaEnqueuesToMq() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);

        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);

        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);

        when(redisIdWorker.nextId("order")).thenReturn(1001L);
        when(stringRedisTemplate.execute(
                anySeckillScript(),
                eq(Collections.emptyList()),
                eq("7"),
                eq("42"),
                eq("1001")
        )).thenReturn(0L);

        Result result = service.seckillVoucher(7L);

        assertTrue(result.getSuccess());
        assertEquals(1001L, result.getData());
        verify(redisIdWorker, times(1)).nextId("order");
        verify(stringRedisTemplate).execute(
                anySeckillScript(),
                eq(Collections.emptyList()),
                eq("7"),
                eq("42"),
                eq("1001")
        );
    }

    /**
     * 回滚前置确认的核心用例。
     *
     * 消息重试耗尽，但数据库里订单已经存在（典型场景：事务提交成功、ACK 之前宕机）。
     * 此时只能确认消息，绝对不能回滚 Redis 的预扣库存和一人一单记录，
     * 否则会变成“数据库有订单 + Redis 库存已归还”，该用户可以再买一次，造成真超卖。
     */
    @Test
    void deadLetterOnlyAcknowledgesWhenOrderAlreadyPersisted() {
        Fixture fixture = Fixture.withExhaustedPendingMessage();
        doReturn(true).when(fixture.service).orderExists(42L, 7L);

        fixture.service.scanPendingOnce();

        verify(fixture.streamOps).acknowledge(STREAM_KEY, GROUP, MESSAGE_ID);
        verify(fixture.streamOps, never()).add(eq(DEAD_KEY), any(Map.class));
        verify(fixture.stringRedisTemplate, never())
                .execute(any(DefaultRedisScript.class), any(List.class), any(Object[].class));
    }

    /**
     * 确认数据库里没有订单之后，才允许留痕并回滚。
     * 顺序必须是：写死信流 -> 执行回滚脚本（脚本内部完成 srem + incrby + xack）。
     */
    @Test
    void deadLetterWritesDeadStreamAndRollsBackWhenOrderMissing() {
        Fixture fixture = Fixture.withExhaustedPendingMessage();
        doReturn(false).when(fixture.service).orderExists(42L, 7L);

        fixture.service.scanPendingOnce();

        verify(fixture.streamOps).add(eq(DEAD_KEY), any(Map.class));
        verify(fixture.stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(java.util.Arrays.asList("seckill:stock:7", "seckill:order:7", STREAM_KEY)),
                eq("42"),
                eq(GROUP),
                eq(MESSAGE_ID.getValue())
        );
        // ACK 由 Lua 脚本完成，Java 侧不再单独调用，避免出现"已 ACK 但未回滚"的窗口。
        verify(fixture.streamOps, never()).acknowledge(STREAM_KEY, GROUP, MESSAGE_ID);
    }

    /**
     * 前置确认本身失败（例如数据库超时）时，什么都不做。
     * 宁可让消息继续留在 pending-list，也不能在数据库状态未知的情况下回滚。
     */
    @Test
    void deadLetterDoesNothingWhenExistenceCheckFails() {
        Fixture fixture = Fixture.withExhaustedPendingMessage();
        doThrow(new IllegalStateException("db timeout")).when(fixture.service).orderExists(42L, 7L);

        fixture.service.scanPendingOnce();

        verify(fixture.streamOps, never()).add(eq(DEAD_KEY), any(Map.class));
        verify(fixture.streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(fixture.stringRedisTemplate, never())
                .execute(any(DefaultRedisScript.class), any(List.class), any(Object[].class));
    }

    /**
     * 投递次数还没到上限的消息走常规重试：用 XCLAIM 重新认领（投递计数 +1），而不是直接停靠死信。
     */
    @Test
    void pendingUnderThresholdIsClaimedForRetryInsteadOfDeadLettered() {
        Fixture fixture = Fixture.withPendingMessage(1L);
        doReturn(true).when(fixture.service).orderExists(42L, 7L);
        when(fixture.streamOps.claim(eq(STREAM_KEY), eq(GROUP), anyString(), any(Duration.class), eq(MESSAGE_ID)))
                .thenReturn(Collections.singletonList(orderRecord()));

        fixture.service.scanPendingOnce();

        verify(fixture.streamOps).claim(eq(STREAM_KEY), eq(GROUP), anyString(), any(Duration.class), eq(MESSAGE_ID));
        verify(fixture.streamOps, never()).add(eq(DEAD_KEY), any(Map.class));
        verify(fixture.streamOps).acknowledge(STREAM_KEY, GROUP, MESSAGE_ID);
    }

    private static MapRecord<String, Object, Object> orderRecord() {
        Map<Object, Object> body = new LinkedHashMap<>();
        body.put("userId", "42");
        body.put("voucherId", "7");
        body.put("id", "1001");
        return MapRecord.create(STREAM_KEY, body).withId(MESSAGE_ID);
    }

    private DefaultRedisScript<Long> anySeckillScript() {
        return any();
    }

    /** 把 pending 扫描所需的 mock 装配集中在一处，让每个用例只表达它关心的那一个差异。 */
    private static final class Fixture {

        private final VoucherOrderServiceImpl service;
        private final StringRedisTemplate stringRedisTemplate;
        private final StreamOperations<String, Object, Object> streamOps;

        private Fixture(VoucherOrderServiceImpl service,
                        StringRedisTemplate stringRedisTemplate,
                        StreamOperations<String, Object, Object> streamOps) {
            this.service = service;
            this.stringRedisTemplate = stringRedisTemplate;
            this.streamOps = streamOps;
        }

        static Fixture withExhaustedPendingMessage() {
            return withPendingMessage(VoucherOrderServiceImpl.MAX_DELIVERY_COUNT);
        }

        static Fixture withPendingMessage(long deliveryCount) {
            VoucherOrderServiceImpl service = spy(new VoucherOrderServiceImpl());
            StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
            StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);

            ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
            ReflectionTestUtils.setField(service, "running", true);
            when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);

            PendingMessage pending = new PendingMessage(
                    MESSAGE_ID,
                    Consumer.from(GROUP, "c1"),
                    Duration.ofSeconds(30),
                    deliveryCount
            );
            when(streamOps.pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), anyLong()))
                    .thenReturn(new PendingMessages(GROUP, Collections.singletonList(pending)));
            when(streamOps.range(eq(STREAM_KEY), any(Range.class)))
                    .thenReturn(Collections.singletonList(orderRecord()));

            return new Fixture(service, stringRedisTemplate, streamOps);
        }
    }
}
