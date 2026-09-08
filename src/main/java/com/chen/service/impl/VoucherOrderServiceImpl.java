package com.chen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.chen.dto.Result;
import com.chen.entity.VoucherOrder;
import com.chen.mapper.VoucherOrderMapper;
import com.chen.service.ISeckillVoucherService;
import com.chen.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.utils.RedisWorker;
import com.chen.utils.ThreadLocalUtils;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisWorker redisWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>("seckill.lua", Long.class);

    private IVoucherOrderService proxy;

    // 线程池
    private final ExecutorService VOUCHER_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        VOUCHER_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 处理线程池中的任务
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }

        // 处理异常订单
        private void handlePendingList() {
        while (true) {
            try {
                // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                // 2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    // 如果为null，说明pending-list里没有异常消息，结束循环
                    break;
                }
                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.创建订单
                handleVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pendding订单异常", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }
    }

    // 创建订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.查询用户信息
        Long userId = voucherOrder.getUserId();
        // 2.创建锁
        SimpleRedisLock lock = new SimpleRedisLock("lock:user:" + userId, stringRedisTemplate);
        // 3.尝试获取锁
        boolean isLock = lock.tryLock(1000L);
        // 4.判断锁是否获取成功
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }

        try {
            //注意：由于是异步线程,所以需要获取主线程的代理对象,解决方案:1.把代理对象设置为全局变量;2.把代理对象作为参数传递
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.执行lua脚本
        Long userId = ThreadLocalUtils.getUser().getId();
        Long orderId = redisWorker.nextId("order:");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),    //注意：lua脚本中没有使用key,所以这里传空列表.且不能传null,因为execute方法内部会遍历keys列表,如果为空,会抛出异常
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        // 2.判断返回值(0:成功 1:库存不足 2:重复下单)
        int r = result.intValue();  //不写也可以,Java会自动拆箱.但是建议写,增强可读性
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单ID
        return Result.success(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断是否在秒杀时间内
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀时间错误");
        }
        // 3.判断库存是否充足
        if (voucher.getStock() <= 0) {
            return Result.fail("库存不足");
        }

        Long userId = ThreadLocalUtils.getUser().getId();
        // 4.解决集群环境下的并发冲突问题
        // 4.1 创建分布式锁
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 4.2 尝试获取锁
        boolean islock = lock.tryLock(120L);
        if (!islock) {
            return Result.fail("不允许重复下单");
        }

        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 4.3 释放锁
            lock.unlock();
        }
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 4.解决一人一单问题
        // 查询用户的订单数量,如果大于0,说明用户购买过一次,不允许重复下单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过一次!");
            return;
        }
        // 5.更新优惠卷库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        // 6.保存订单
        save(voucherOrder);
    }
}
