package com.aschen.redis.javaAPI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;


@SpringBootTest
public class equalsTest {
    @Autowired
    public StringRedisTemplate stringRedisTemplate;
    public static final String KEY_PREFIX = "eq?";

    /*
    redis`s ""  == java`s ""
    * */

    @Test
    public void test1(){
        stringRedisTemplate.opsForValue().set(KEY_PREFIX,"");

        String nullone = stringRedisTemplate.opsForValue().get(KEY_PREFIX);

        System.out.println("nullone==\"\" = " + nullone == ""); //false


        System.out.println("nullone.equals(\"\") = " + nullone.equals("")); //true
        /*
        * 注意redis的"" 和 "" 是不一样的
        * 要比较内容最好还是用 equals
        */
    }

    /*比较字符串用常量.equals 防止变量为null*/

    @Test
    public void test2(){
        String s = stringRedisTemplate.opsForValue().get(KEY_PREFIX);
        System.out.println("".equals(s));;
    }

    @Test
    public void test3(){
        stringRedisTemplate.opsForValue().set("66666","hello");
        String s = stringRedisTemplate.opsForValue().get("66666");

        String s1= "hello";

        System.out.println("s1==s = " + s1 == s);
        System.out.println("s1.equals(s) = " + s1.equals(s));
    }


}
