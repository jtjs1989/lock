package com.cb.lock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import redis.clients.jedis.exceptions.JedisException;

@Component
public class StringRedisTemplateExt implements FactoryBean, InitializingBean {

	private static final Logger logger = LoggerFactory.getLogger(StringRedisTemplateExt.class);
	
	private Object proxyObject;
	
	public StringRedisTemplateExt(StringRedisTemplate redisTemplate) {
		this.proxyObject = redisTemplate;
	}
	
	/**
	 * redis断路器
	 * true 代表执行远程Redis调用
	 * false 代表不执行远程Redis调用
	 */
	private volatile boolean redisOpen;
	/**
	 * 窗口期失败次数
	 */
	private int excpTimes = 30;
	/**
	 * 窗口期时间  单位秒
	 */
	private int windowPhase = 60;
	
	private static Queue<Long> queue = new LinkedList<>();
	
	private Object lock = new Object();
	
	/**
	 * 异常时调用
	 */
	private void callWhenException() {
		if (!redisOpen) {
			return;
		}
		long concurrentTime = System.currentTimeMillis();
		synchronized (lock) {
			queue.add(concurrentTime);
			if (queue.size() > excpTimes) {
				long start = queue.poll();
				if ((concurrentTime - start) < windowPhase * 1000) {
					logger.error("redis exception times:{}, windouPhase:{}", queue.size(), (start-concurrentTime));
					redisOpen = false;
					// 定时任务检查
				}
			}
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		// TODO Auto-generated method stub
		proxyObject = Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[] {RedisOperations.class}, new InvocationHandler() {
			
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				// TODO Auto-generated method stub
				try {
					
					return method.invoke(proxy, args);
				} catch (JedisException e) {
					// TODO: handle exception
					throw e;
				} finally {
					// TODO: handle finally clause
				}
			}
		});
	}

	@Override
	public Object getObject() throws Exception {
		return proxyObject;
	}

	@Override
	public Class getObjectType() {
		return proxyObject.getClass();
	}
	
}
