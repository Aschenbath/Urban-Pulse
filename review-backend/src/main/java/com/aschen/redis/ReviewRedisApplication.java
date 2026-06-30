package com.aschen.redis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.aschen.redis.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
public class ReviewRedisApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewRedisApplication.class, args);
    }
}
