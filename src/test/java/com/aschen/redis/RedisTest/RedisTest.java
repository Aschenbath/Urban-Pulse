package com.aschen.redis.RedisTest;


import ch.qos.logback.classic.Level;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;


@Slf4j
@SpringBootTest
public class RedisTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public static final String key = "USER_1";
    public static final Long time = 10L;


    @Test
    void String1() {
        String key = "article:1001:views";

        // 初始设置为 0
        stringRedisTemplate.opsForValue().set(key, "0");

        // 模拟 3 次访问
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.opsForValue().increment(key);

        // 获取最终结果
        String result = stringRedisTemplate.opsForValue().get(key);

        System.out.println("文章 1001 的浏览量: " + result);
        // 你应该会看到：文章 1001 的浏览量: 3


    }


    @Test
    void hash() {

        //设置一个用户的信息 USER_1 hashkey value
        stringRedisTemplate.opsForHash().put(key, "name", "zhangsan");
        stringRedisTemplate.opsForHash().put(key, "age", "18");
        stringRedisTemplate.opsForHash().put(key, "sex", "男");

        //获取一个哈希表中的所有字段和值用的是entries方法
        Map<Object, Object> user = stringRedisTemplate.opsForHash().entries(key);
        System.out.println(user);

        System.out.println();
        //获取哈希表某个字段的值
        System.out.println("name is: " + stringRedisTemplate.opsForHash().get(key, "name"));

        System.out.println("age is: " + stringRedisTemplate.opsForHash().get(key, "age"));
    }


    @Test
    void list3() {

        //listname content
        stringRedisTemplate.opsForList().leftPush("tasks", "task1");
        stringRedisTemplate.opsForList().leftPush("tasks", "task2");
        stringRedisTemplate.opsForList().leftPush("tasks", "task3");

        System.out.println(stringRedisTemplate.opsForList().range("tasks", 0, -1));

        System.out.println();
        System.out.println("stringRedisTemplate.opsForList().index(\"tasks\",5) = " + stringRedisTemplate.opsForList().index("tasks", 5));


        //删除指定的元素 key index value
        stringRedisTemplate.opsForList().remove("tasks", 1, "task2");

        System.out.println(stringRedisTemplate.opsForList().range("tasks", 0, -1));
    }


    @Test
    void zset4() {
        stringRedisTemplate.opsForZSet().add("myzset", "a", 1);
        stringRedisTemplate.opsForZSet().add("myzset", "b", 2);
        stringRedisTemplate.opsForZSet().add("myzset", "c", 3);
        stringRedisTemplate.opsForZSet().add("myzset", "d", 4);


        //按第三维度从低到高排序
        System.out.println(stringRedisTemplate.opsForZSet().range("myzset", 0, -1));

        //按第三维度从高到低排序
        System.out.println(stringRedisTemplate.opsForZSet().reverseRange("myzset", 0, -1));

        //按第三维度从低到高排序，并返回分数
        System.out.println(stringRedisTemplate.opsForZSet().rangeByScore("myzset", 1, 3));
        System.out.println(stringRedisTemplate.opsForZSet().getOperations().delete("myzset"));
//        stringRedisTemplate.opsForZSet().
    }

    @Test
    void set5() {
        stringRedisTemplate.opsForSet().add("myset", "a", "b", "c", "d", "e");
        System.out.println(stringRedisTemplate.opsForSet().members("myset"));
        System.out.println(stringRedisTemplate.opsForSet().isMember("myset", "a"));


        //delete needs getOperations
        stringRedisTemplate.opsForSet().getOperations().delete("myset");

    }


}
