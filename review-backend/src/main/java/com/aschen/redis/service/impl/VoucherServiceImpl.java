package com.aschen.redis.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aschen.redis.dto.Result;
import com.aschen.redis.entity.Voucher;
import com.aschen.redis.mapper.VoucherMapper;
import com.aschen.redis.entity.SeckillVoucher;
import com.aschen.redis.service.ISeckillVoucherService;
import com.aschen.redis.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;

import static com.aschen.redis.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        /*
         * 预热必须推迟到事务提交之后。
         *
         * Redis 不参与 Spring 事务。如果在这里直接写，而后续事务回滚（比如上面任何一条 INSERT
         * 违反约束，或调用方在更外层的事务里抛异常），就会留下"Redis 有库存、MySQL 没有券"的状态。
         * 这个方向是最坏的：Lua 只校验 Redis，会照常放行请求并预扣库存，而消费者写 MySQL 必然
         * 失败，形成一条永远处理不掉的毒药消息，把消费线程连同后面所有正常订单一起拖死。
         *
         * 挪到 afterCommit 之后，两个存储之间仍然没有原子性（没有 2PC 就不可能有），但剩下的
         * 失败方向变成了"MySQL 有券、Redis 没库存"：这一侧由 seckill.lua 的返回码 3 负责报错，
         * 用户看到的是"活动准备中"而不是被错误劝退，重跑一次预热即可修复。
         * 也就是说这里不是消除了不一致，而是把它赶到可发现、可低成本修复的那一侧。
         */
        Long voucherId = voucher.getId();
        Integer stock = voucher.getStock();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    warmUpAfterCommit(voucherId, stock);
                }
            });
        } else {
            // 没有活跃事务时 registerSynchronization 会抛 IllegalStateException，直接写即可。
            warmUpAfterCommit(voucherId, stock);
        }
    }

    /**
     * afterCommit 阶段的预热。
     *
     * 这里吞掉异常只记日志，因为此刻数据库事务已经提交、券确实已经创建成功了；
     * 往外抛会让调用方以为整个创建操作失败，与事实不符。预热失败留给返回码 3 的告警
     * 和将来的对账任务收敛。
     */
    private void warmUpAfterCommit(Long voucherId, Integer stock) {
        try {
            warmUpSeckillStock(voucherId, stock);
            log.info("秒杀库存预热完成，voucherId={}, stock={}", voucherId, stock);
        } catch (Exception e) {
            log.error("秒杀库存预热失败，券已创建但 Redis 无库存，抢购会返回“活动准备中”，voucherId={}",
                    voucherId, e);
        }
    }

    @Override
    public void warmUpSeckillStock(Long voucherId, Integer stock) {
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, stock.toString());
    }
}
