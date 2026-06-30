package com.aschen.redis.service.impl;

import com.aschen.redis.dto.Result;
import com.aschen.redis.dto.UserDTO;
import com.aschen.redis.utils.RedisIdWorker;
import com.aschen.redis.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoucherOrderServiceImplTest {

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

    private DefaultRedisScript<Long> anySeckillScript() {
        return any();
    }
}
