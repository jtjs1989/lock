
package com.cb.lock;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可重入锁的线程计数器
 * @author chenbo.jtjs<chenbo.jtjs @ bytedance.com>
 * @date 09/23/2019 3:02 下午
 */
public class LockThreadLocalManager {

    private static final ThreadLocal<Map<String, AtomicInteger>> Lock = new ThreadLocal<>();

    public static int getLockTimes(String token) {
        ensureNotNull();
        return Lock.get().get(token) == null ? 0 : Lock.get().get(token).get();
    }

    public static void firstLockSuccess(String token) {
        ensureNotNull();
        Lock.get().put(token, new AtomicInteger(1));

    }

    public static void reentry(String token) {
        ensureNotNull();
        Lock.get().get(token).incrementAndGet();
    }

    public static Integer release(String token) {
        return Lock.get().get(token).decrementAndGet();
    }

    public static void clean(String token) {
        Map local = Lock.get();
        local.remove(token);
        if (local.isEmpty()) {
            Lock.remove();
        }
    }

    private static void ensureNotNull() {
        Map<String, AtomicInteger> local = Lock.get();
        if (local == null) {
            local = new HashMap<>();
            Lock.set(local);
        }
    }
}
