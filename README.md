# lock
redis实现的分布式锁
实现了jdk的Lock接口，使用方法同Lock类似 不支持condition
提供了锁重入的支持
提供了优先本地锁的支持，先获取本地JVM锁，然后再尝试获取redis锁


分布式锁与本地事务配合使用时要注意的事项
考虑如下代码
@Transactional
	public void update() {
		Lock lock = new RedisDistributionLock("test", 10000);
		lock.lock();
		try{
			//数据库操作
			select * from table where id = ?
			update table set ** = ?;
		} finally {
			lock.unlock();
		}
	}
  以上代码存在的问题： 分布式锁会在事务提交前被释放，另外的线程会读到事务变更前的数据，与预期的逻辑不符合
  正确的使用方法：
  @Transactional
	public void update() {
		Lock lock = new RedisDistributionLock("test", 10000);
		lock.lock();
		try{
			//数据库操作
			select * from table where id = ?
			update table set ** = ?;
		} finally {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter(){
				@Override
				public void afterCommit() {
					lock.unlock();
				}
			});
		}
	}
