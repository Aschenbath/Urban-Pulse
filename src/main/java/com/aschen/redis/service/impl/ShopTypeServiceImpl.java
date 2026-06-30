package com.aschen.redis.service.impl;

import cn.hutool.json.JSONUtil;
import com.aschen.redis.entity.ShopType;
import com.aschen.redis.mapper.ShopTypeMapper;
import com.aschen.redis.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.aschen.redis.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource private StringRedisTemplate stringRedisTemplate;


    //利用zset
    @Override
    public List<ShopType> queryByType() {
        //查询redis缓存
        Set<String> range = stringRedisTemplate.opsForZSet().range(CACHE_SHOPTYPE_KEY, 0, -1);
        if (range != null && !range.isEmpty()) {
            List<ShopType> shopTypes =new ArrayList<>();
            for (String s : range) {
                shopTypes.add(JSONUtil.toBean(s, ShopType.class));
            }
            shopTypes.sort(Comparator.comparing(ShopType::getSort));
            return shopTypes;
        }

        //不存在，查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if (shopTypes == null){return new ArrayList<>();}

        //存入redis
        for (ShopType shopType : shopTypes) {
            //zset三个维度, key value score
           stringRedisTemplate.opsForZSet().add(CACHE_SHOPTYPE_KEY,JSONUtil.toJsonStr(shopType),shopType.getSort());
        }
        
        return shopTypes;
    }
}
