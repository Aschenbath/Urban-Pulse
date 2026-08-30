package com.aschen.redis.service;

import com.aschen.redis.dto.Result;
import com.aschen.redis.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类我
 * </p>
 *
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);

    /**
     * 把秒杀库存写入 Redis。抽成独立方法是为了让预热成为一个有名字、可被单独调用的动作。
     *
     * 这里是无条件覆盖，因为调用场景是"券刚创建，Redis 库存就该等于初始值"，
     * 覆盖才是正确语义；用 setIfAbsent 反而会在 Redis 存有同名残留 key 时（例如数据库被
     * 重置导致自增 id 重复）让新券静默拿到旧库存。
     *
     * 将来做对账任务时不能直接复用本方法，要单独写一个：对账必须用 setIfAbsent 只补缺失的 key，
     * 并且写入 MySQL 当前的剩余库存而不是初始库存，否则会把一场正在进行的秒杀重置回初始值，
     * 等于凭空发券。
     */
    void warmUpSeckillStock(Long voucherId, Integer stock);
}
