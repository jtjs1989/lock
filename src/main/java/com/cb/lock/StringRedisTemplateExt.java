package com.cb.lock;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class StringRedisTemplateExt extends StringRedisTemplate {

	private static final Logger logger = LoggerFactory.getLogger(StringRedisTemplateExt.class);
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
	
}
