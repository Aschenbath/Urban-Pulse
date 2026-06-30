package com.aschen.redis.utils;

import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Ilock{

    /*不同的jvm的线程虽然不同,但是其线程id都是自增的,可能会有冲突,因此要加上UUID
    * 注意这里我们用的是value作为检验的标准
    *   key：锁的名字（业务标识）
        value：谁持有锁（UUID + threadId）
        释放锁：必须检查 value 是否匹配 → 确保安全
    */
    private static final String KEY_PREFIX = "lock:";//锁的统一前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPE;//静态成员变量, 可以在静态代码块实现初始化
    static {
        UNLOCK_SCRIPE = new DefaultRedisScript<>();//软编码
        UNLOCK_SCRIPE.setLocation((Resource) new ClassPathResource("unlock.lua"));//指定lua脚本 在classpath的resource找
        UNLOCK_SCRIPE.setResultType(Long.class);//指定返回值类型
    }

    private String name;//业务名
    private StringRedisTemplate stringRedisTemplate;
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeout) {
        //获取当前线程的标识 setnx
        String threadId =ID_PREFIX + Thread.currentThread().getId();
        String key = KEY_PREFIX + name;
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId, timeout, TimeUnit.SECONDS);//设置过期时间保底
        return BooleanUtil.isTrue(success);
    }


    @Override
    public void unLock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPE,
                Collections.singletonList(KEY_PREFIX + name), //要集合 , 此处是单元素集合
                ID_PREFIX + Thread.currentThread().getId());
    }

    /*由于此处是多个redis操作,查询, 判断, 释放
     因此需要原子性,因此使用lua脚本--->能够在一句执行完
    SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    存入时 key = lock:order:1, value = uuid-threadId
    取时是 key = lock:order:?, get value ,然后判断是否一致

    @Override
    public void unLock() {//检查锁标示是否和当前线程的一致 即当前key得到的value是否是当前线程的标识
        //获取当前线程的标识
        String currThreadId =ID_PREFIX+ Thread.currentThread().getId();
        //获取锁的标识
        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        //判断是否一致
        if(currThreadId.equals(lockId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);//release lock
        }
    }*/
}
