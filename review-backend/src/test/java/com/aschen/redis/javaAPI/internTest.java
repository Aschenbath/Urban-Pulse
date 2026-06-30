package com.aschen.redis.javaAPI;

public class internTest {
    public static void main(String[] args) {
        String s1 = new String("hello");
        String s2 = "hello";
        System.out.println(s1 == s2);//constant value vs heap

        String s3 = new String("hello");
        System.out.println(s1 == s3);//different object

        String s4 = "hello";
        System.out.println(s2 == s4);

        System.out.println(s2.intern());
        String intern = s2.intern();
        System.out.println(intern == s2);
    }
}
