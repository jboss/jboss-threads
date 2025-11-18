/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.threads;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests for checking EnhancedTreadPoolExecutor
 * <p>
 */
public class EnhancedThreadQueueExecutorTestCase {

    private int coreSize = 4;
    private int maxSize = 7;
    private long keepaliveTimeMillis = 1000;
    private long defaultWaitTimeout = 5000;

    class TestTask implements Runnable {
        CountDownLatch exitLatch;
        CountDownLatch allThreadsRunningLatch;

        private TestTask(CountDownLatch exitLatch, CountDownLatch allThreadsRunningLatch) {
            this.exitLatch = exitLatch;
            this.allThreadsRunningLatch = allThreadsRunningLatch;
        }

        @Override
        public void run() {
            try {
                allThreadsRunningLatch.countDown();
                exitLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Test invalid values:
     * * Negative keepAlive, coreSize, maxSize
     * * maxSize > coreSize
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidValuesKeepAliveZero() {
        new EnhancedQueueExecutor.Builder()
                .setKeepAliveTime(0, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidValuesKeepAliveNegative() {
        new EnhancedQueueExecutor.Builder()
                .setKeepAliveTime(-3456, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidValuesCoreSizeNegative() {
        new EnhancedQueueExecutor.Builder()
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .setCorePoolSize(-5)
                .setMaximumPoolSize(maxSize)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidValuesMaxSizeNegative() {
        new EnhancedQueueExecutor.Builder()
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(-3)
                .build();
    }

    @Test
    public void testCoreSizeBiggerThanMaxSize() {
        int expectedCorePoolSize = 5;
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .setCorePoolSize(2 * expectedCorePoolSize)
                .setMaximumPoolSize(expectedCorePoolSize)
                .build();
        Assert.assertEquals("Core size should be automatically adjusted to be equal to max size in case it's bigger.", expectedCorePoolSize, executor.getCorePoolSize());
    }

    /**
     * Test that unused threads are being reused. Scenario:
     * <ul>
     * <li>max threads = 2x, core threads = x</li>
     * <li>schedule x tasks, wait for tasks to finish</li>
     * <li>schedule x tasks, expect pool size = x immediately after</li>
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

        CountDownLatch exitLatch = new CountDownLatch(1);
        CountDownLatch allThreadsRunningLatch = new CountDownLatch(coreSize);

        for (int i = 0; i < coreSize; i++) {
            executor.execute(new TestTask(exitLatch, allThreadsRunningLatch));
        }
        Assert.assertTrue("Not all threads were running. They were most likely not scheduled for execution.",
                allThreadsRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS));
        waitForPoolSize(executor, coreSize, defaultWaitTimeout);
        exitLatch.countDown();
        waitForActiveCount(executor, 0, defaultWaitTimeout);
        waitForPoolSize(executor, coreSize, defaultWaitTimeout);
        exitLatch = new CountDownLatch(1);
        allThreadsRunningLatch = new CountDownLatch(coreSize);
        for (int i = 0; i < coreSize; i++) {
            executor.execute(new TestTask(exitLatch, allThreadsRunningLatch));
        }
        Assert.assertTrue("Not all threads were running. They were most likely not scheduled for execution.",
                allThreadsRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS));
        exitLatch.countDown();
        waitForPoolSize(executor, coreSize, defaultWaitTimeout);
        executor.shutdown();
    }

    /**
     * Test thread reuse above core size
     * Scenario:
     * <ul>
     * <li>setKeepAlive=60 sec</li>
     * <li>max threads = 2x, core threads = x</li>
     * <li>schedule x tasks and wait to occupy all core threads </li>
     * <li>schedule one more task and let it finish</li>
     * <li>schedule one task and check that pool size is still x+1</li>
     * </ul>
     */
    @Test
    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void testThreadReuseAboveCoreSize() throws TimeoutException, InterruptedException {
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(60000, TimeUnit.MILLISECONDS)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();

        // submit 3 tasks to fill core size
        CountDownLatch exitLatch = new CountDownLatch(1);
        CountDownLatch allThreadsRunningLatch = new CountDownLatch(coreSize);
        for (int i = 0; i < coreSize; i++) {
            executor.execute(new TestTask(exitLatch, allThreadsRunningLatch));
        }
        Assert.assertTrue("Not all threads were running. They were most likely not scheduled for execution.",
                allThreadsRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS));
        waitForPoolSize(executor, coreSize, defaultWaitTimeout);

        // submit one more task and allow it to finish
        CountDownLatch singleExitLatch = new CountDownLatch(1);
        CountDownLatch threadRunningLatch = new CountDownLatch(1);
        executor.execute(new TestTask(singleExitLatch, threadRunningLatch));
        threadRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS);
        waitForPoolSize(executor, coreSize + 1, defaultWaitTimeout);
        singleExitLatch.countDown();
        waitForActiveCount(executor, coreSize, defaultWaitTimeout);

        // now there are just core threads and one free thread, submit another task and check it's reused
        singleExitLatch = new CountDownLatch(1);
        threadRunningLatch = new CountDownLatch(1);
        executor.execute(new TestTask(singleExitLatch, threadRunningLatch));
        threadRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS);
        waitForPoolSize(executor, coreSize + 1, defaultWaitTimeout);
        singleExitLatch.countDown();

        // finish all
        exitLatch.countDown();
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
                .allowCoreThreadTimeOut(false)
                .build();

        CountDownLatch exitLatch = new CountDownLatch(1);
        CountDownLatch allThreadsRunningLatch = new CountDownLatch(coreSize);
        for (int i = 0; i < coreSize; i++) {
            executor.execute(new TestTask(exitLatch, allThreadsRunningLatch));
        }
        Assert.assertTrue("Not all core threads are running. They were most likely not scheduled for execution.",
                allThreadsRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS));
        CountDownLatch exitLatch2 = new CountDownLatch(1);
        CountDownLatch allThreadsRunningLatch2 = new CountDownLatch(maxSize - coreSize);
        for (int i = 0; i < (maxSize - coreSize); i++) {
            executor.execute(new TestTask(exitLatch2, allThreadsRunningLatch2));
        }
        Assert.assertTrue("Not all above core threads were running. They were most likely not scheduled for execution.",
                allThreadsRunningLatch2.await(defaultWaitTimeout, TimeUnit.MILLISECONDS));
        waitForPoolSize(executor, maxSize, defaultWaitTimeout);

        // finish core tasks and let timeout "core" threads
        exitLatch.countDown();
        waitForActiveCount(executor, maxSize - coreSize, defaultWaitTimeout);
        waitForPoolSize(executor, Math.max(coreSize, (maxSize - coreSize)), defaultWaitTimeout);
        exitLatch2.countDown();
        waitForActiveCount(executor, 0, defaultWaitTimeout);
        waitForPoolSize(executor, coreSize, defaultWaitTimeout);
        executor.shutdown();
    }

    /**
     * Test that keepalive time is ignored when core threads are the same as max
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

        CountDownLatch exitLatch = new CountDownLatch(1);
        CountDownLatch allThreadsRunningLatch = new CountDownLatch(coreSize);

        for (int i = 0; i < coreSize; i++) {
            executor.execute(new TestTask(exitLatch, allThreadsRunningLatch));
        }
        Assert.assertTrue("Not all threads were running. They were most likely not scheduled for execution.",
                allThreadsRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS));
        waitForPoolSize(executor, coreSize, defaultWaitTimeout);
        exitLatch.countDown();
        waitForActiveCount(executor, 0, defaultWaitTimeout);
        waitForPoolSize(executor, coreSize, defaultWaitTimeout);
        executor.shutdown();
    }

    /**
     * Test the keepalive setting with core thread time out enabled.
     */
    @Test
    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void testKeepaliveTimeWithCoreThreadTimeoutEnabled() throws TimeoutException, InterruptedException {
        EnhancedQueueExecutor executor = (new EnhancedQueueExecutor.Builder())
                .setKeepAliveTime(keepaliveTimeMillis, TimeUnit.MILLISECONDS)
                .allowCoreThreadTimeOut(true)
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .build();

        CountDownLatch exitLatch = new CountDownLatch(1);
        CountDownLatch allThreadsRunningLatch = new CountDownLatch(maxSize);

        for (int i = 0; i < maxSize; i++) {
            executor.execute(new TestTask(exitLatch, allThreadsRunningLatch));
        }
        // this will make sure that all thread are running at the same time
        Assert.assertTrue("Not all threads were running. They were most likely not scheduled for execution.",
                allThreadsRunningLatch.await(defaultWaitTimeout, TimeUnit.MILLISECONDS));
        exitLatch.countDown();
        waitForActiveCount(executor, 0, defaultWaitTimeout);
        waitForPoolSize(executor, 0, defaultWaitTimeout);
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
        Assert.assertEquals("expected: == " + coreSize + ", actual: " + prestarted, coreSize, prestarted);
        Assert.assertEquals("expected: == " + coreSize + ", actual: " + executor.getPoolSize(), coreSize, executor.getPoolSize());
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

    @Test
    public void testEnhancedExecutorShutdownNoTasks() throws Exception {
        final CountDownLatch terminateLatch = new CountDownLatch(1);
        EnhancedQueueExecutor executor = new EnhancedQueueExecutor.Builder()
                .setCorePoolSize(10)
                .setKeepAliveTime(1, TimeUnit.NANOSECONDS)
                .setTerminationTask(new Runnable() {
                    @Override
                    public void run() {
                        terminateLatch.countDown();
                    }
                })
                .build();

        executor.shutdown();
        Assert.assertTrue(terminateLatch.await(10, TimeUnit.SECONDS));
    }

    @Test //JBTHR-50
    public void testEnhancedExecutorShutdown() throws Exception {
        final CountDownLatch terminateLatch = new CountDownLatch(1);
        EnhancedQueueExecutor executor = new EnhancedQueueExecutor.Builder()
                .setCorePoolSize(10)
                .setKeepAliveTime(1, TimeUnit.NANOSECONDS)
                .setTerminationTask(new Runnable() {
                    @Override
                    public void run() {
                        terminateLatch.countDown();
                    }
                })
                .build();

        for (int i = 0; i < 10000; ++i) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        //ignore
                    }
                }
            });
        }
        executor.shutdown();
        Assert.assertTrue(terminateLatch.await(10, TimeUnit.SECONDS));
    }

    /**
     * Test that the Duration-based setKeepAliveTime method works correctly.
     * This verifies compatibility with Infinispan 15.0.19+ which uses Duration API.
     */
    @Test
    public void testSetKeepAliveTimeWithDuration() throws Exception {
        // Test Builder.setKeepAliveTime(Duration)
        EnhancedQueueExecutor executor = new EnhancedQueueExecutor.Builder()
                .setCorePoolSize(coreSize)
                .setMaximumPoolSize(maxSize)
                .setKeepAliveTime(Duration.ofMillis(keepaliveTimeMillis))
                .build();

        // Verify the keepalive time was set correctly
        long actualKeepAliveMillis = executor.getKeepAliveTime(TimeUnit.MILLISECONDS);
        Assert.assertEquals("KeepAlive time should match the value set via Duration API",
                keepaliveTimeMillis, actualKeepAliveMillis);

        // Test runtime setKeepAliveTime(Duration) on the executor instance
        long newKeepAliveMillis = 5000;
        executor.setKeepAliveTime(Duration.ofMillis(newKeepAliveMillis));
        actualKeepAliveMillis = executor.getKeepAliveTime(TimeUnit.MILLISECONDS);
        Assert.assertEquals("KeepAlive time should match the value set via Duration API at runtime",
                newKeepAliveMillis, actualKeepAliveMillis);

        executor.shutdown();
    }
}
