package com.aschen.redis;

import com.aschen.redis.entity.Shop;
import com.aschen.redis.service.IShopService;
import com.aschen.redis.service.impl.ShopServiceImpl;
import com.aschen.redis.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.sql.Time;
import java.util.concurrent.TimeUnit;

import static com.aschen.redis.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class ReviewRedisApplicationTests {


    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cc;


    @Test
    void contextLoads() {
        Shop shop=shopService.getById(1L);
        cc.setWithLogicalExpire(CACHE_SHOP_KEY+"1", shop,10L, TimeUnit.SECONDS);
    }
}
