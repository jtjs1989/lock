package com.cb.lock;

import java.util.Arrays;
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
 * 使用时需要注意线程重入的情况，不同于jdk的锁，此锁无法做到线程重入
 * Lock lock = new RedisDistributionLock(lockKey);
 * lock.lock();
 * try{
 *    dosomthing();
 * }finally{
 * 	  lock.unlock();
 * }
 * @author chenbo
 *
 */
public class RedisDistributionLock implements Lock {

	private static final Logger log = LoggerFactory.getLogger(RedisDistributionLock.class);
	
	public static final String KeyPref = "lock_";
	private static final int defaultExpTime = 10 * 1000; //默认缓存时间 10秒
	private StringRedisTemplate redisTemplate;
	private String lockKey;
	private String value;
	private boolean isLock;
	/**
	 * lockKey过期时间
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
	 * 获取锁失败 线程 sleep 时间
	 */
	private long threadSleepTime;
	/**
	 * 释放锁时删除锁的Lua脚本
	 */
	private static final DefaultRedisScript<Boolean> redisScript = new DefaultRedisScript<Boolean>(
			"if redis.call(\"get\",KEYS[1]) == ARGV[1] then \n"+
			"    redis.call(\"del\",KEYS[1]) \n"+
			"	 return true \n" +
			"else \n"+
			"	return false \n" +
			"end"
			, Boolean.class);
	/**
	 * 
	 * @param lockKey 锁的key
	 * @param redisTemplate
	 * @param expire 超时时间  单位为毫秒
	 */
	public RedisDistributionLock(String lockKey, StringRedisTemplate redisTemplate, int expire, int thradSleepTime) {
		this.redisTemplate = redisTemplate;
		this.lockKey = KeyPref + lockKey;
		this.expire = expire;
		this.threadSleepTime = thradSleepTime;
	}
	public RedisDistributionLock(String lockKey, StringRedisTemplate redisTemplate, int expire) {
		this(lockKey, redisTemplate, expire, defaultThreadSleepTime);
	}
	/**
	 * 构造方法   此构造方法需要spring容器中存在StringRedisTemplate 这个bean
	 * @param lockKey
	 */
	public RedisDistributionLock(String lockKey) {
		this(lockKey, SpringContextUtil.getBean(StringRedisTemplate.class) , defaultExpTime);
	}
	
	public RedisDistributionLock(String lockKey, int exp) {
		this(lockKey, SpringContextUtil.getBean(StringRedisTemplate.class), exp);
	}
	/**
	 * 
	 * @param lockKey
	 * @param exp   缓存key的过期时长
	 * @param threadSleepTime  线程休眠时间
	 */
	public RedisDistributionLock(String lockKey, int exp, int threadSleepTime) {
		this(lockKey, SpringContextUtil.getBean(StringRedisTemplate.class), exp, threadSleepTime);
	}
	@Override
	public void lock() {
		if (tryLock()) {
			return ;
		} else {
			waitForLock(Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public void lockInterruptibly() throws InterruptedException {
		throw new InterruptedException();
	}

	@Override
	public boolean tryLock() {
		try {
			if (log.isDebugEnabled()) {
				log.info("begin tryLock [{}]", lockKey);
			}
			lockTimestamp = System.currentTimeMillis();
			String end = (lockTimestamp + expire) + "";
			boolean success = redisSetNx(lockKey, end, expire);
			if (success) {
				lockedTimestamp = lockTimestamp;
				value = end;
				if (log.isDebugEnabled()) {
					log.info("tryLock success :{}",lockKey);
				}
				isLock = true;
			}
			return success;
		} catch (Exception e) {
			log.error("获取锁异常：{}", lockKey);
		} 
		return false;
	}

	@Override
	public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
		if (tryLock()) {
			return true;
		} 
		return waitForLock(time, unit);
	}

	private boolean waitForLock(long waitTime, TimeUnit unit) {
		try {
			long time = unit.toMillis(waitTime);
			int times = 0; //循环加锁次数
			while(lockTimestamp + time > System.currentTimeMillis()){
				long timestamp = System.currentTimeMillis();
				String redValue = (timestamp + expire) + "";
				boolean success = redisSetNx(lockKey, redValue, expire);;
				times++;
				if (success) {
					value = redValue;
					lockedTimestamp = timestamp;
					isLock = true;
					if (log.isDebugEnabled()) {
						log.info("locked:{}, try_lock_times:{}",lockKey, times);
					}
					return true;
				} else {
					TimeUnit.MICROSECONDS.sleep(threadSleepTime);
				}
			}
		} catch (InterruptedException e) {
			log.error("Thread.sleep 异常", e);
			e.printStackTrace();
			throw new RuntimeException(e);
		} 
		return false;
	}
	@Override
	public void unlock() {
		if (isLock) {
			redisTemplate.execute(redisScript, Arrays.asList(lockKey), value);
			if (log.isDebugEnabled()) {
				log.info("unlock[{}], lockedTimes:{}ms", lockKey, (System.currentTimeMillis()-lockedTimestamp));
			}
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
}
