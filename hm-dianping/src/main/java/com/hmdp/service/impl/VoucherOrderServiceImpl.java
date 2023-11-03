package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.ILock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.simpleRedisLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    ISeckillVoucherService iSeckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }

    //代理对线共事务使用
    IVoucherOrderService proxy;

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024 * 1024);
    @Override
    public Result getOrder(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //判断结果是否为0
        int r = result.intValue();
        if (r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        long orderId = redisIdWorker.nextId("order");
        //保存到阻塞队列，操作数据库
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }



    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //阻塞获取
                    VoucherOrder voucherOrder= orderTasks.take();
                    //处理订单
                    handleVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("处理订单异常");
                }
            }
        }
        public void handleVoucherOrder(VoucherOrder voucherOrder){
            Long userId = voucherOrder.getUserId();
            //创建锁对象
//        ILock lock = new simpleRedisLock(stringRedisTemplate,"order");
            //使用redisson
            RLock lock = redissonClient.getLock("order:" + userId);
            //获取锁(分布式锁)
            boolean tryLock = lock.tryLock();
            if (!tryLock) {

                //保险，防止redis出意外
                log.error("不允许重复下单");
                return;
            }

            /* * transaction调用自身方法不会触发事物
             *可以获得代理对象然后调用其方法
             *
             *
             * 锁的位置应该加在这
             * 避免事物未提交锁已经释放
             **/
            try {

              /*  代理对象式通过threadLocal拿的所以现在拿不到了，所以把他提到局部变量提前获取*/
                proxy.createVoucherOrder(voucherOrder);
            }finally {
                lock.unlock();
            }
        }
    }


    //秒杀优化前
   /* @Override
    public Result getOrder(Long voucherId) {
        //获取秒杀券
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        //检查是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //没开始
            return Result.fail("活动没开始");
        }
        //检查是否过期
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //没开始
            return Result.fail("活动已结束");
        }
        //检查库存
        if (voucher.getStock()<1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();


        //创建锁对象
        ILock lock = new simpleRedisLock(stringRedisTemplate,"order");
        //使用redisson
        RLock lock = redissonClient.getLock("order:" + userId);
        //获取锁(分布式锁)
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            return Result.fail("一个人只能购买一次");
        }

            * transaction调用自身方法不会触发事物
            *可以获得代理对象然后调用其方法
            *
            *
            * 锁的位置应该加在这
            * 避免事物未提交锁已经释放
            *
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }


    }*/




    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getId();
        //保证一人一单
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count>0){
            log.error("已经购买过了");
           return;
        }

        //扣除库存
        iSeckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id",voucherId)
                .gt("stock",0)//乐观锁
                .update();
        //获取订单
        save(voucherOrder);

    }
}
