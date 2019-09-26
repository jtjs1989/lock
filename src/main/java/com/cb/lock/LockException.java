package com.cb.lock;

/**
 * 获取锁异常
 * @author chenbo
 * @date 09/26/2019 3:07 下午
 */
public class LockException extends RuntimeException {
    public LockException(String msg) {
        super(msg);
    }
}
