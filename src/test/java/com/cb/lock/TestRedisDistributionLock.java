package com.cb.lock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations="classpath:spring.xml")
public class TestRedisDistributionLock extends AbstractJUnit4SpringContextTests{

	@Test
	public void testTryLock() {
		Lock lock = new RedisDistributionLock("test", 10*1000);
		lock.lock();
		if (lock.tryLock()) {
			try {
				System.out.println("trylock success");
			} finally {
				lock.unlock();
			}
		}
	}
	@Test
	public void testMultiThreadLock() {
		CountDownLatch count = new CountDownLatch(10);
		for (int i = 0; i < 10; i++) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					Lock lock = new RedisDistributionLock("test", 10*1000);
					lock.lock();
					try {
						System.out.println("trylock success");
						try {
							Thread.sleep(10);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					} finally {
						count.countDown();
						lock.unlock();
					}
				}
			}).start();
		}
		try {
			count.await();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
