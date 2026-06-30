package com.aschen.redis.service;

import com.aschen.redis.dto.Result;
import com.aschen.redis.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopTypeService extends IService<ShopType> {
    List<ShopType> queryByType();
}
