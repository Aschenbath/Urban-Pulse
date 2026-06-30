package com.aschen.redis.utils;

public interface Ilock {
    boolean tryLock(Long timeout);
    void unLock();
}
