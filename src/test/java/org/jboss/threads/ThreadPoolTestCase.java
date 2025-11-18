/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.TestCase;
import org.junit.Ignore;

/**
 *
 */
public final class ThreadPoolTestCase extends TestCase {

    private final JBossThreadFactory threadFactory = new JBossThreadFactory(null, null, null, "test thread %p %t", null, null);

    private static final class SimpleTask implements Runnable {

        private final CountDownLatch taskUnfreezer;
        private final CountDownLatch taskFinishLine;

        private SimpleTask(final CountDownLatch taskUnfreezer, final CountDownLatch taskFinishLine) {
            this.taskUnfreezer = taskUnfreezer;
            this.taskFinishLine = taskFinishLine;
        }

        public void run() {
            try {
                assertTrue(taskUnfreezer.await(800L, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail("interrupted");
            }
            taskFinishLine.countDown();
        }
    }

    public void testBasic() throws InterruptedException {
        // Start some tasks, let them run, then shut down the executor
        final int cnt = 100;
        final CountDownLatch taskUnfreezer = new CountDownLatch(1);
        final CountDownLatch taskFinishLine = new CountDownLatch(cnt);
        final ExecutorService simpleQueueExecutor = new QueueExecutor(5, 5, 500L, TimeUnit.MILLISECONDS, 1000, threadFactory, true, null);
        for (int i = 0; i < cnt; i ++) {
            simpleQueueExecutor.execute(new SimpleTask(taskUnfreezer, taskFinishLine));
        }
        taskUnfreezer.countDown();
        final boolean finished = taskFinishLine.await(800L, TimeUnit.MILLISECONDS);
        assertTrue(finished);
        simpleQueueExecutor.shutdown();
        try {
            simpleQueueExecutor.execute(new Runnable() {
                public void run() {
                }
            });
            fail("Task not rejected after shutdown");
        } catch (Throwable t) {
            assertTrue(t instanceof RejectedExecutionException);
        }
        assertTrue(simpleQueueExecutor.awaitTermination(800L, TimeUnit.MILLISECONDS));
    }

    public void testShutdownNow() throws InterruptedException {
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean ran = new AtomicBoolean();

        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finLatch = new CountDownLatch(1);
        final ExecutorService simpleQueueExecutor = new QueueExecutor(5, 5, 500L, TimeUnit.MILLISECONDS, 1000, threadFactory, true, null);
        simpleQueueExecutor.execute(new Runnable() {
            public void run() {
                try {
                    ran.set(true);
                    startLatch.countDown();
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } finally {
                    finLatch.countDown();
                }
            }
        });
        assertTrue("Task not started", startLatch.await(300L, TimeUnit.MILLISECONDS));
        assertTrue("Task returned", simpleQueueExecutor.shutdownNow().isEmpty());
        try {
            simpleQueueExecutor.execute(new Runnable() {
                public void run() {
                }
            });
            fail("Task not rejected after shutdown");
        } catch (RejectedExecutionException t) {
        }
        assertTrue("Task not finished", finLatch.await(300L, TimeUnit.MILLISECONDS));
        assertTrue("Executor not shut down in 800ms", simpleQueueExecutor.awaitTermination(800L, TimeUnit.MILLISECONDS));
        assertTrue("Task wasn't run", ran.get());
        assertTrue("Worker wasn't interrupted", interrupted.get());
    }

    private static class Holder<T> {
        private T instance;
        public Holder(T instance) {
            this.instance = instance;
        }
        public T get() { return instance; }
        public void set(T instance) {this.instance = instance;}
    }

    public void testBlocking() throws InterruptedException {
        final int queueSize = 20;
        final int coreThreads = 5;
        final int extraThreads = 5;
        final int cnt = queueSize + coreThreads + extraThreads;
        final CountDownLatch taskUnfreezer = new CountDownLatch(1);
        final CountDownLatch taskFinishLine = new CountDownLatch(cnt);
        final ExecutorService simpleQueueExecutor = new QueueExecutor(coreThreads, coreThreads + extraThreads, 500L, TimeUnit.MILLISECONDS, new ArrayQueue<Runnable>(queueSize), threadFactory, true, null);
        for (int i = 0; i < cnt; i ++) {
            simpleQueueExecutor.execute(new SimpleTask(taskUnfreezer, taskFinishLine));
        }
        Thread.currentThread().interrupt();
        try {
            simpleQueueExecutor.execute(new Runnable() {
                public void run() {
                }
            });
            fail("Task was accepted");
        } catch (RejectedExecutionException t) {
        }
        Thread.interrupted();
        final CountDownLatch latch = new CountDownLatch(1);
        final Thread otherThread = threadFactory.newThread(new Runnable() {
            public void run() {
                simpleQueueExecutor.execute(new Runnable() {
                    public void run() {
                        latch.countDown();
                    }
                });
            }
        });
        otherThread.start();
        assertFalse("Task executed without wait", latch.await(100L, TimeUnit.MILLISECONDS));
        // safe to say the other thread is blocking...?
        taskUnfreezer.countDown();
        assertTrue("Task never ran", latch.await(800L, TimeUnit.MILLISECONDS));
        otherThread.join(500L);
        assertTrue("Simple Tasks never ran", taskFinishLine.await(800L, TimeUnit.MILLISECONDS));
        simpleQueueExecutor.shutdown();
        final Holder<Boolean> callback = new Holder<Boolean>(false);
        final CountDownLatch shutdownLatch = new CountDownLatch(1);
        ((QueueExecutor)simpleQueueExecutor).addShutdownListener(new EventListener<Object>() {
            @Override
            public void handleEvent(Object attachment) {
                callback.set(true);
                shutdownLatch.countDown();
            } } , null);
        shutdownLatch.await(100L, TimeUnit.MILLISECONDS);
        assertTrue("Calback not called", callback.get());
        assertTrue("Executor not shut down in 800ms", simpleQueueExecutor.awaitTermination(800L, TimeUnit.MILLISECONDS));
    }

    public void testBlockingEmpty() throws InterruptedException {
        final int queueSize = 20;
        final int coreThreads = 5;
        final int extraThreads = 5;
        final int cnt = queueSize + coreThreads + extraThreads;
        final ExecutorService simpleQueueExecutor = new QueueExecutor(coreThreads, coreThreads + extraThreads, 500L, TimeUnit.MILLISECONDS, new ArrayQueue<Runnable>(queueSize), threadFactory, true, null);
        simpleQueueExecutor.shutdown();
        final Holder<Boolean> callback = new Holder<Boolean>(false);
        ((QueueExecutor)simpleQueueExecutor).addShutdownListener(new EventListener<Object>() {
            @Override
            public void handleEvent(Object attachment) {
                callback.set(true);
            } } , null);
        assertTrue("Calback not called", callback.get());
        assertTrue("Executor not shut down in 800ms", simpleQueueExecutor.awaitTermination(800L, TimeUnit.MILLISECONDS));
        Thread.interrupted();
    }

    public void testQueuelessEmpty() throws InterruptedException {
        final int queueSize = 20;
        final int coreThreads = 5;
        final int extraThreads = 5;
        final int cnt = queueSize + coreThreads + extraThreads;
        final ExecutorService simpleQueueExecutor = new QueuelessExecutor(threadFactory, SimpleDirectExecutor.INSTANCE, null, 500L);
        simpleQueueExecutor.shutdown();
        final Holder<Boolean> callback = new Holder<Boolean>(false);
        ((QueuelessExecutor)simpleQueueExecutor).addShutdownListener(new EventListener<Object>() {
            @Override
            public void handleEvent(Object attachment) {
                callback.set(true);
            } } , null);
        assertTrue("Calback not called", callback.get());
        assertTrue("Executor not shut down in 800ms", simpleQueueExecutor.awaitTermination(800L, TimeUnit.MILLISECONDS));
        Thread.interrupted();
    }

    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void ignore_testQueuelessKeepAlive() throws InterruptedException {
        // Test for https://issues.jboss.org/browse/JBTHR-32 QueuelessExecutor doesn't shrink with keepAliveTime
        final QueuelessExecutor simpleQueuelessExecutor = new QueuelessExecutor(threadFactory, SimpleDirectExecutor.INSTANCE, null, 100L);
        simpleQueuelessExecutor.setMaxThreads(1);
        final Holder<Thread> thread1 = new Holder<Thread>(null);
        simpleQueuelessExecutor.execute(new Runnable() {
                boolean first = true;
                public void run() {
                    thread1.set(Thread.currentThread());
                }
            });
        try {
            Thread.sleep(500L);
        } catch (InterruptedException ignore) { }
        final CountDownLatch latch = new CountDownLatch(1);
        final Holder<Thread> thread2 = new Holder<Thread>(null);
        simpleQueuelessExecutor.execute(new Runnable() {
                public void run() {
                    thread2.set(Thread.currentThread());
                    latch.countDown();
                }
            });
        latch.await(100L, TimeUnit.MILLISECONDS);
        assertTrue("First task and second task should be executed on different threads", thread1.get() != thread2.get());
        simpleQueuelessExecutor.shutdown();
        assertTrue("Executor not shut down in 800ms", simpleQueuelessExecutor.awaitTermination(800L, TimeUnit.MILLISECONDS));
        Thread.interrupted();
    }

    @Ignore("https://issues.jboss.org/browse/JBTHR-67")
    public void ignore_testQueuelessKeepAliveRepeating() throws InterruptedException {
        // Test for https://issues.jboss.org/browse/JBTHR-23 QueuelessExecutor repeats to execute previous runnable
        final QueuelessExecutor simpleQueuelessExecutor = new QueuelessExecutor(threadFactory, SimpleDirectExecutor.INSTANCE, null, 100L);
        simpleQueuelessExecutor.setMaxThreads(1);
        final CountDownLatch latch = new CountDownLatch(1);
        simpleQueuelessExecutor.execute(new Runnable() {
                public void run() {
                    latch.countDown();
                }
            });
        latch.await(100L, TimeUnit.MILLISECONDS);
        final Holder<Boolean> callback = new Holder<Boolean>(false);
        simpleQueuelessExecutor.execute(new Runnable() {
                boolean first = true;
                public void run() {
                    callback.set(true);
                    if (!first) {
                        callback.set(false);
                    }
                    first = false;
                }
            });
        try {
            Thread.sleep(500L);
        } catch (InterruptedException ignore) { }
        simpleQueuelessExecutor.shutdown();
        assertTrue("Calback only called once", callback.get());
        assertTrue("Executor not shut down in 800ms", simpleQueuelessExecutor.awaitTermination(800L, TimeUnit.MILLISECONDS));
        Thread.interrupted();
    }

    public void testQueueRejection() throws InterruptedException {
        final int queueSize = 1;
        final int coreThreads = 1;
        final int extraThreads = 0;
        final int cnt = queueSize + coreThreads + extraThreads;

        final ExecutorService queueExecutor = new QueueExecutor(coreThreads, coreThreads + extraThreads, 500L, TimeUnit.MILLISECONDS, queueSize, threadFactory, false, null);
        final CountDownLatch taskUnfreezer = new CountDownLatch(1);
        final Runnable task =  new Runnable() {
			public void run() {
				boolean running = true;
				while (running) {
					try {
						taskUnfreezer.await();
						running = false;
					} catch (InterruptedException e) {
					}
				}
			}
        };

        try {
            try {
                for (int i = 0; i < cnt; i++) {
                    queueExecutor.execute(task);
                }
            } catch (RejectedExecutionException e) {
                fail("unexpected RejectedExecutionException");
            }
            try {
                queueExecutor.execute(task);
                fail("Task not rejected after too many tasks");
            } catch (RejectedExecutionException e) {
                // expected
            }
        } finally {
            taskUnfreezer.countDown();
            queueExecutor.shutdown();
        }
    }
}

