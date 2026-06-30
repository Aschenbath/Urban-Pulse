package com.aschen.redis.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.cookie.ThreadLocalCookieStore;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aschen.redis.dto.Result;
import com.aschen.redis.entity.Shop;
import com.aschen.redis.mapper.ShopMapper;
import com.aschen.redis.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aschen.redis.utils.CacheClient;
import com.aschen.redis.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.aschen.redis.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

        @Resource private StringRedisTemplate stringRedisTemplate;

        @Resource private CacheClient cacheClient;

            @Override
            public Result queryShopById(Long id) {
            // 1. 解决缓存穿透
            Shop shop = cacheClient
                    .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

            // 2. 互斥锁解决缓存击穿
            // Shop shop = cacheClient
            //         .setWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

////           3. 逻辑过期解决缓存击穿
//             Shop shop = cacheClient
//                     .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

            if (shop == null) {
                return Result.fail("店铺不存在！");
            }
            // 7.返回
            return Result.ok(shop);
        }



    @Override  @Transactional(rollbackFor = Exception.class)
    public Result updateShop(Shop shop) {
            Long id = shop.getId();
            if(id==null){
                return Result.fail("店铺id不能为空");
            }
            /*database system got first
            * cache the second
            * promise that the data in the cache is always the latest
            * */
            //1. 更新数据库
            updateById(shop);

            //2. 删除缓存
            String key= CACHE_SHOP_KEY + id;
            stringRedisTemplate.delete(key);

            return Result.ok();
        }



}
