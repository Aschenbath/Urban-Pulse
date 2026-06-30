package com.aschen.redis.controller;


import com.aschen.redis.dto.Result;
import com.aschen.redis.entity.SeckillVoucher;
import com.aschen.redis.entity.VoucherOrder;
import com.aschen.redis.service.ISeckillVoucherService;
import com.aschen.redis.service.IVoucherOrderService;
import com.aschen.redis.service.IVoucherService;
import com.aschen.redis.utils.RedisIdWorker;
import com.aschen.redis.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService iVoucherOrderService;

    @PostMapping("/seckill/{id}")//这里是用户id
    public Result seckillVoucher(@PathVariable("id") Long id) {
            return iVoucherOrderService.seckillVoucher(id);
    }

}
