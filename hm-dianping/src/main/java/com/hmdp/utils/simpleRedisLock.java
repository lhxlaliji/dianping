package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class simpleRedisLock implements ILock{

    StringRedisTemplate stringRedisTemplate;

    String name;


    public simpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString();
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }
    @Override
    public boolean tryLock(long timeSec) {
        String value = ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX+name,
                value,
                timeSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
    //使用lua脚本保持操作的原子性
    stringRedisTemplate.execute(
            UNLOCK_SCRIPT,
            Collections.singletonList(KEY_PREFIX+name),
            ID_PREFIX+Thread.currentThread().getId()
            );
    }

    /*    @Override
    public void unlock() {
        *//*
         * 为了避免在特殊情况下当前线程误删了其他线程的锁，在删前做一个识别
         * *//*
        String value = ID_PREFIX+Thread.currentThread().getId();

        String LockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        if (value.equals(LockId)){
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }



    }*/

}
