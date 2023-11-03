package com.hmdp.utils;

public interface ILock {

    /**
     * 实现分布式锁的操作
     *
     *
     */

    boolean tryLock(long timeSec);

    void unlock();
}
