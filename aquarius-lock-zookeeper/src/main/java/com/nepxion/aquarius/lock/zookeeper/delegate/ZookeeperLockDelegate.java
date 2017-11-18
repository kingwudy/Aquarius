package com.nepxion.aquarius.lock.zookeeper.delegate;

/**
 * <p>Title: Nepxion Aquarius</p>
 * <p>Description: Nepxion Aquarius</p>
 * <p>Copyright: Copyright (c) 2017</p>
 * <p>Company: Nepxion</p>
 * @author Haojun Ren
 * @email 1394997@qq.com
 * @version 1.0
 */

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.InterProcessReadWriteLock;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.nepxion.aquarius.common.constant.AquariusConstant;
import com.nepxion.aquarius.common.curator.constant.CuratorConstant;
import com.nepxion.aquarius.common.curator.handler.CuratorHandler;
import com.nepxion.aquarius.common.exception.AquariusException;
import com.nepxion.aquarius.common.property.AquariusProperties;
import com.nepxion.aquarius.lock.delegate.LockDelegate;
import com.nepxion.aquarius.lock.entity.LockType;

public class ZookeeperLockDelegate implements LockDelegate {
    private static final Logger LOG = LoggerFactory.getLogger(ZookeeperLockDelegate.class);

    private CuratorFramework curator;

    @Value("${" + AquariusConstant.PREFIX + "}")
    private String prefix;

    // 可重入锁可重复使用
    private volatile Map<String, InterProcessMutex> lockMap = new ConcurrentHashMap<String, InterProcessMutex>();
    private volatile Map<String, InterProcessReadWriteLock> readWriteLockMap = new ConcurrentHashMap<String, InterProcessReadWriteLock>();
    private boolean lockCached = true;

    @PostConstruct
    public void initialize() {
        try {
            AquariusProperties config = CuratorHandler.createPropertyConfig(CuratorConstant.CONFIG_FILE);
            curator = CuratorHandler.createCurator(config);

            String rootPath = CuratorHandler.getRootPath(prefix);
            if (!CuratorHandler.pathExist(curator, rootPath)) {
                CuratorHandler.createPath(curator, rootPath, CreateMode.PERSISTENT);
            }
        } catch (Exception e) {
            LOG.error("Initialize Curator failed", e);
        }
    }

    @PreDestroy
    public void destroy() {
        CuratorHandler.closeCurator(curator);
    }

    @Override
    public Object invoke(MethodInvocation invocation, LockType lockType, String key, long leaseTime, long waitTime, boolean async, boolean fair) throws Throwable {
        if (curator == null) {
            throw new AquariusException("Curator isn't initialized");
        }

        if (!CuratorHandler.isStarted(curator)) {
            throw new AquariusException("Curator isn't started");
        }

        if (fair) {
            throw new AquariusException("Fair lock of Zookeeper isn't support for " + lockType);
        }

        if (async) {
            throw new AquariusException("Async lock of Zookeeper isn't support for " + lockType);
        }

        return invokeLock(invocation, lockType, key, leaseTime, waitTime);
    }

    private Object invokeLock(MethodInvocation invocation, LockType lockType, String key, long leaseTime, long waitTime) throws Throwable {
        InterProcessMutex interProcessMutex = null;
        try {
            interProcessMutex = getLock(lockType, key);
            boolean status = interProcessMutex.acquire(waitTime, TimeUnit.MILLISECONDS);
            if (status) {
                return invocation.proceed();
            }
        } finally {
            unlock(interProcessMutex);
        }

        return null;
    }

    private InterProcessMutex getLock(LockType lockType, String key) {
        if (lockCached) {
            return getCachedLock(lockType, key);
        } else {
            return getNewLock(lockType, key);
        }
    }

    private InterProcessMutex getNewLock(LockType lockType, String key) {
        String path = CuratorHandler.getPath(prefix, key);
        switch (lockType) {
            case LOCK:
                return new InterProcessMutex(curator, path);
            case READ_LOCK:
                return getCachedReadWriteLock(lockType, key).readLock();
                // return new InterProcessReadWriteLock(curator, path).readLock();
            case WRITE_LOCK:
                return getCachedReadWriteLock(lockType, key).writeLock();
                // return new InterProcessReadWriteLock(curator, path).writeLock();
        }

        throw new AquariusException("Invalid Zookeeper lock type for " + lockType);
    }

    private InterProcessMutex getCachedLock(LockType lockType, String key) {
        String path = CuratorHandler.getPath(prefix, key);
        String newKey = path + "-" + lockType;

        InterProcessMutex lock = lockMap.get(newKey);
        if (lock == null) {
            InterProcessMutex newLock = getNewLock(lockType, key);
            lock = lockMap.putIfAbsent(newKey, newLock);
            if (lock == null) {
                lock = newLock;
            }
        }

        return lock;
    }

    private InterProcessReadWriteLock getCachedReadWriteLock(LockType lockType, String key) {
        String path = CuratorHandler.getPath(prefix, key);
        String newKey = path;

        InterProcessReadWriteLock readWriteLock = readWriteLockMap.get(newKey);
        if (readWriteLock == null) {
            InterProcessReadWriteLock newReadWriteLock = new InterProcessReadWriteLock(curator, path);
            readWriteLock = readWriteLockMap.putIfAbsent(newKey, newReadWriteLock);
            if (readWriteLock == null) {
                readWriteLock = newReadWriteLock;
            }
        }

        return readWriteLock;
    }

    private void unlock(InterProcessMutex interProcessMutex) throws Throwable {
        if (CuratorHandler.isStarted(curator)) {
            if (interProcessMutex.isAcquiredInThisProcess()) {
                interProcessMutex.release();
            }
        }
    }
}