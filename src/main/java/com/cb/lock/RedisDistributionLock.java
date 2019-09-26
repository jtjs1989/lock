package com.cb.lock;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.types.Expiration;

/**
 * 基于redis的分布式锁
 * 使用API同  {@link java.util.concurrent.locks.Lock}
 * 通过使用ThreadLocal来做线程重入
 * Lock lock = new RedisDistributionLock(lockKey);
 * lock.lock();
 * try{
 * dosomthing();
 * }finally{
 * lock.unlock();
 * }
 *
 * @author chenbo
 */
public class RedisDistributionLock implements Lock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributionLock.class);

    private static final Map<String, Semaphore> jvm_lock_map = new ConcurrentHashMap<>();

    public static final String KeyPref = "lock_";
    //默认缓存时间 10秒
    private static final int defaultExpTime = 10 * 1000;
    private StringRedisTemplate redisTemplate;
    private String lockKey;
    private String value;
    private boolean isLock;
    /**
     * 是否使用JVM本地同步锁
     */
    private boolean useJvmLock = true;
    /**
     * lock过期时间
     */
    private int expire;
    /**
     * 开始获取锁的时间戳
     */
    private long lockTimestamp;
    /**
     * 成功获取锁的时间戳
     */
    private long lockedTimestamp;

    private static final int defaultThreadSleepTime = 5;
    /**
     * 获取锁失败的线程 sleep 时间
     */
    private long threadSleepTime;
    /**
     * 释放锁时删除锁的Lua脚本
     */
    private static final DefaultRedisScript<Boolean> redisScript = new DefaultRedisScript<Boolean>(
    		"if redis.call(\"get\",KEYS[1]) == ARGV[1] then \n" +
					"    redis.call(\"del\",KEYS[1]) \n" +
                    "	 return true \n" +
                    "else \n" +
                    "	return false \n" +
                    "end"
            , Boolean.class);

    /**
     * @param lockKey       锁的key
     * @param redisTemplate
     * @param expire        超时时间  单位为毫秒
     */
    public RedisDistributionLock(String lockKey, StringRedisTemplate redisTemplate, int expire, int threadSleepTime, boolean useJvmLock) {
        this.redisTemplate = redisTemplate;
        this.lockKey = KeyPref + lockKey;
        this.expire = expire;
        this.threadSleepTime = threadSleepTime;
        this.lockTimestamp = System.currentTimeMillis();
        this.useJvmLock = useJvmLock;
    }

    public RedisDistributionLock(String lockKey, StringRedisTemplate redisTemplate, int expire) {
        this(lockKey, redisTemplate, expire, defaultThreadSleepTime, true);
    }

    /**
     * 构造方法   此构造方法需要spring容器中存在StringRedisTemplate 这个bean
     *
     * @param lockKey
     */
    public RedisDistributionLock(String lockKey) {
        this(lockKey, SpringContextUtil.getBean(StringRedisTemplate.class), defaultExpTime);
    }

    public RedisDistributionLock(String lockKey, int exp) {
        this(lockKey, SpringContextUtil.getBean(StringRedisTemplate.class), exp);
    }

    /**
     * @param lockKey
     * @param exp             缓存key的过期时长
     * @param threadSleepTime 线程休眠时间
     */
    public RedisDistributionLock(String lockKey, int exp, int threadSleepTime) {
        this(lockKey, SpringContextUtil.getBean(StringRedisTemplate.class), exp, threadSleepTime, true);
    }

    @Override
    public void lock() {
        if (useJvmLock) {
            acquireLock(-1, null, Integer.MAX_VALUE);
        } else {
            internalLock(-1, null, Integer.MAX_VALUE);
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        lock();
    }

    @Override
    public boolean tryLock() {
        if (useJvmLock) {
            return tryAcquireLock();
        } else {
            return internalLock(-1, null, 1);
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        if (useJvmLock) {
            return acquireLock(time, unit, Integer.MAX_VALUE);
        } else {
            return internalLock(time, unit, Integer.MAX_VALUE);
        }
    }

    /**
     * 真正的加锁逻辑,
     * 支持优先本地获取锁，获取到本地锁到线程再去redis轮训获取分布式锁
     *
     * @param time  同步等待锁的时间
     * @param unit
     * @param times 允许执行加锁的次数
     * @return
     */
    private boolean internalLock(long time, TimeUnit unit, final int times) {
        int state = LockThreadLocalManager.getLockTimes(lockKey);
        if (state > 0) {
            LockThreadLocalManager.reentry(lockKey);
            return true;
        }
        if (time < 0 || unit == null) {
            time = Integer.MAX_VALUE;
            unit = TimeUnit.MILLISECONDS;
        }
        boolean hasException = false;
        try {
            int lockTimes = 0;
            while (lockTimestamp + unit.toMillis(time) > System.currentTimeMillis() && lockTimes < times) {
                long timestamp = System.currentTimeMillis();
                String redValue = String.valueOf(timestamp + expire);
                boolean success = redisSetNx(lockKey, redValue, expire);
                lockTimes++;
                if (success) {
                    LockThreadLocalManager.firstLockSuccess(lockKey);
                    value = redValue;
                    lockedTimestamp = timestamp;
                    isLock = true;
                    if (log.isDebugEnabled()) {
                        log.info("Thread[{}] acquired lock[{}], try_lock_times:{}", Thread.currentThread().getId(), lockKey, lockTimes);
                    }
                    return true;
                } else {
                    try {
                        TimeUnit.MILLISECONDS.sleep(threadSleepTime);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("lock exception",e);
            hasException = true;
        }
        if (hasException) {
            deleteKey();
            throw new LockException("获取锁异常");
        }
        return false;
    }

    /**
     * 释放锁时判断线程退出次数与重入次数是否对应，当线程计数器减为0时才真正执行redis的key 删除操作
     */
    @Override
    public void unlock() {
        int state = LockThreadLocalManager.release(lockKey);
        if (state <= 0) {
            LockThreadLocalManager.clean(lockKey);
            deleteKey();
            release();
        }
    }

    private void deleteKey() {
        redisTemplate.execute(redisScript, Arrays.asList(lockKey), value);
        if (log.isDebugEnabled()) {
            log.info("unlock[{}], lockedTimes:{}ms", lockKey, (System.currentTimeMillis() - lockedTimestamp));
        }
    }

    @Override
    public Condition newCondition() {
    	return null;
    }

    private byte[] seri(String val) {
        if (val == null) {
            throw new NullPointerException("");
        }
        return redisTemplate.getStringSerializer().serialize(val);
    }

    /**
     * 包装redis set 方法
     *
     * @param key
     * @param value
     * @param expire 过期时间  单位为毫秒
     * @return
     */
    private Boolean redisSetNx(String key, String value, long expire) {
        RedisConnection connection = RedisConnectionUtils.getConnection(redisTemplate.getConnectionFactory());
        try {
            return connection.set(seri(key), seri(value), Expiration.milliseconds(expire), SetOption.SET_IF_ABSENT);
        } finally {
            RedisConnectionUtils.releaseConnection(connection, redisTemplate.getConnectionFactory());
        }
    }

    /**
     * 阻塞获取锁
     * 首先获取JVM本地锁，获取成功再去获取Redis分布式锁
     * @param time
     * @param unit
     * @param times
     * @throws InterruptedException
     * @throws LockException
     */
    private boolean acquireLock(long time, TimeUnit unit, final int times) throws LockException {
        Semaphore semaphore = getSemaphore();
        try {
            semaphore.acquire();
            return internalLock(time, unit, times);
        } catch (LockException e) {
            semaphore.release();
            throw e;
        } catch (InterruptedException e) {
            e.printStackTrace();
            log.error("",e);
        }
        return false;
    }

    /**
     * 尝试一次获取锁
     * @return
     */
    private boolean tryAcquireLock() {
        Semaphore semaphore = getSemaphore();
        if (semaphore.tryAcquire()) {
            try {
                if (internalLock(-1, null, 1)) {
                    return true;
                } else {
                    semaphore.release();
                }
            } catch (LockException e) {
                semaphore.release();
            }
        }
        return false;
    }

    private void release() {
        getSemaphore().release();
    }

    private Semaphore getSemaphore() {
        Semaphore semaphore = jvm_lock_map.get(lockKey);
        if (semaphore == null) {
            semaphore = new Semaphore(1);
            jvm_lock_map.putIfAbsent(lockKey, semaphore);
            semaphore = jvm_lock_map.get(lockKey);
        }
        return semaphore;
    }

    public void setUseJvmLock(boolean useJvmLock) {
        this.useJvmLock = useJvmLock;
    }
}
