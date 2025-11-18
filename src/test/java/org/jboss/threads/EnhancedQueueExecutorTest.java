package org.jboss.threads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Ignore;
import org.junit.Test;

public class EnhancedQueueExecutorTest {
    private int coreSize = 3;
    private int maxSize = coreSize * 2;
    private long keepaliveTimeMillis = 1000;

    class TestTask implements Runnable {
        private long sleepTime = 0;

        public TestTask withSleepTime(long sleepTime) {
            if (sleepTime > 0) {
                this.sleepTime = sleepTime;
            }
            return this;
        }

        @Override
        public void run() {
            try {
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Test that unused threads are being reused. Scenario:
     * <ul>
     *     <li>max threads = 2x, core threads = x</li>
     *     <li>schedule x tasks, wait for tasks to finish</li>
     *     <li>schedule x tasks, expect pool size = x immediately after</li>
     * </ul>
     */
    @Test
    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void testThreadReuse() throws TimeoutException, InterruptedException {
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();

        for (int i = 0; i < coreSize; i++) {
            executor.execute(new TestTask().withSleepTime(100));
        }
        assertEquals("expected: == " + coreSize + ", actual: " + executor.getPoolSize(), executor.getPoolSize(), coreSize);
        waitForActiveCount(executor, 0, 1000);
        assertEquals("expected: == " + coreSize + ", actual: " + executor.getPoolSize(), executor.getPoolSize(), coreSize);
        for (int i = 0; i < coreSize; i++) {
            executor.execute(new TestTask().withSleepTime(1000));
        }
        assertEquals("expected: == " + coreSize + ", actual: " + executor.getPoolSize(), executor.getPoolSize(), coreSize);
        executor.shutdown();
    }

    /**
     * Test that keepalive time is honored and threads above the core count are being removed when no tasks are
     * available.
     *
     * @throws InterruptedException
     * @throws TimeoutException
     */
    @Test
    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void testKeepaliveTime() throws TimeoutException, InterruptedException {
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();

        assertTrue("expected: <=" + coreSize + ", actual: " + executor.getPoolSize(), executor.getPoolSize() <= coreSize);
        for (int i = 0; i < maxSize; i++) {
            executor.execute(new TestTask().withSleepTime(1000));
        }
        assertEquals("expected: ==" + maxSize + ", actual: " + executor.getPoolSize(), executor.getPoolSize(), maxSize);
        waitForActiveCount(executor, 0, 5000);
        waitForPoolSize(executor, coreSize, keepaliveTimeMillis * 2);
        executor.shutdown();
    }

    /**
     * Test that max size setting is honored. Test that keepalive time is ignored when core threads are the same as max
     * threads and core thread time out is disabled.
     */
    @Test
    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void testKeepaliveTime2() throws TimeoutException, InterruptedException {
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(coreSize)
                .build();

        for (int i = 0; i < 2*coreSize; i++) {
            executor.execute(new TestTask().withSleepTime(100));
        }
        int currentThreads = executor.getPoolSize();
        assertEquals("expected: == " + coreSize + ", actual: " + currentThreads, currentThreads, coreSize);
        waitForActiveCount(executor, 0, 5000);
        assertEquals("expected: == " + currentThreads + ", actual: " + executor.getPoolSize(), executor.getPoolSize(), currentThreads);
        executor.shutdown();
    }

    /**
     * Test the keepalive setting with core thread time out enabled.
     */
    @Test
    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void testKeepaliveTime3() throws TimeoutException, InterruptedException {
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .allowCoreThreadTimeOut(true)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();

        for (int i = 0; i < maxSize; i++) {
            executor.execute(new TestTask().withSleepTime(0));
        }
        waitForActiveCount(executor, 0, 5000);
        waitForPoolSize(executor, 0, keepaliveTimeMillis * 2);
        executor.shutdown();
    }

    /**
     * Tests that prestarting core threads starts exactly the core threads amount specified.
     */
    @Test
    public void testPrestartCoreThreads() {
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();
        int prestarted = executor.prestartAllCoreThreads();
        assertEquals("expected: == " + coreSize + ", actual: " + prestarted, prestarted, coreSize);
        assertEquals("expected: == " + coreSize + ", actual: " + executor.getPoolSize(), executor.getPoolSize(), coreSize);
        executor.shutdown();
    }

    private void waitForPoolSize(EnhancedQueueExecutor executor, int expectedPoolSize, long waitMillis) throws TimeoutException, InterruptedException {
        long deadline = System.currentTimeMillis() + waitMillis;
        long delayMillis = 100;

        do {
            if (executor.getPoolSize() == expectedPoolSize) {
                break;
            }
            Thread.sleep(delayMillis);
        } while (System.currentTimeMillis() + delayMillis < deadline);
        if (executor.getPoolSize() != expectedPoolSize) {
            throw new TimeoutException("Timed out waiting for pool size to become " + expectedPoolSize
                    + ", current pool size is " + executor.getPoolSize());
        }
    }

    private void waitForActiveCount(EnhancedQueueExecutor executor, int expectedActiveCount, long waitMillis) throws TimeoutException, InterruptedException {
        long deadline = System.currentTimeMillis() + waitMillis;
        long delayMillis = 100;

        do {
            if (executor.getActiveCount() == expectedActiveCount) {
                break;
            }
            Thread.sleep(delayMillis);
        } while (System.currentTimeMillis() + delayMillis < deadline);
        if (executor.getActiveCount() != expectedActiveCount) {
            throw new TimeoutException("Timed out waiting for active count to become " + expectedActiveCount
                    + ", current active count is " + executor.getActiveCount());
        }
    }
}
