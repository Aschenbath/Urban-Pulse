package com.aschen.redis.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
//生成全局唯一ID
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;//start timestamp
    //序列号的位数
    private static final int COUNT_BITS = 32;

    //use create method to call redis
    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //生成全局唯一ID
    public long nextId(String keyPrefix){
        //1.生成timestamp
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);//获取当前时间戳
        long timestamp = nowSecond - BEGIN_TIMESTAMP;//计算当前时间戳与开始时间戳的差值 as real timestamp

        //2.生成序列号
        //2.1获得当前日期--->每天都会有新的key按日期更新 --- keyPrefix 其实是一个业务名
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //2.2生成序列号
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接组成id --> 符号位(1)+时间戳(31)+序列号(32)
        return (timestamp << COUNT_BITS) | count;
    }
}
