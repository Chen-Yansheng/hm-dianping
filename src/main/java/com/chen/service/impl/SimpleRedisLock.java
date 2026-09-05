package com.chen.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.chen.utils.ILock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 解决集群环境下的并发冲突问题
 * 采用Redis的SETNX命令实现分布式锁
 */
public class SimpleRedisLock implements ILock {

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String THREAD_ID_PREFIX = UUID.randomUUID().toString() + ":";

    /**
     * 尝试获取锁("尝试",并不保证成功获取,获取失败时不会一直重复获取,防止阻塞)
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        // 获取线程标识
        String threadId = THREAD_ID_PREFIX + Thread.currentThread().getId();
        // 尝试获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    /**
     * 释放锁
     */
    // 防误删二(线程持有锁时阻塞至锁过期时间,判断是否是当前线程的锁 和 删除锁不是同时执行,期间其他线程拿锁,线程继续执行释放锁,即释放了其他线程的锁):lua脚本使判断和删除锁操作原子化
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("unlock.lua", Long.class);

    @Override
    public void unlock() {
        String threadId = THREAD_ID_PREFIX + Thread.currentThread().getId();
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                threadId
        );
    }

    /*@Override
    public void unlock() {
        // 防误删一(线程持有锁时阻塞至锁过期时间,阻塞结束业务完成又释放锁,即可能释放了其他线程的锁):删锁时判断是否是当前线程的锁
        // 获取线程标识
        String threadId = THREAD_ID_PREFIX + Thread.currentThread().getId();
        // 获取当前锁中的线程标识
        String currentThreadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(currentThreadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
