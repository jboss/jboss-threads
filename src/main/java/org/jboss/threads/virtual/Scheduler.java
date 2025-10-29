package org.jboss.threads.virtual;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import io.smallrye.common.annotation.Experimental;
import io.smallrye.common.constraint.Assert;
import jdk.internal.vm.ThreadContainer;
import org.jboss.threads.EnhancedQueueExecutor;

/**
 * A virtual thread scheduler.
 */
@Experimental("Experimental virtual thread support")
public final class Scheduler implements Executor {
    private final EnhancedQueueExecutor blockingPool;
    private final Container container = new Container();
    private final AtomicInteger eventLoopIdx = new AtomicInteger(1);
    private final AtomicLong threadIdx = new AtomicLong(1);
    private final PoolDispatcher poolDispatcher = new PoolDispatcher();

    /**
     * Construct a new instance.
     *
     * @param blockingPool the blocking pool to use (must not be {@code null})
     */
    Scheduler(final EnhancedQueueExecutor blockingPool) {
        this.blockingPool = Assert.checkNotNullParam("blockingPool", blockingPool);
    }

    /**
     * Construct and start a new event loop thread for this scheduler.
     *
     * @param eventLoop the event loop to use (must not be {@code null})
     * @return the new event loop thread (not {@code null})
     * @throws NullPointerException if the factory returned a {@code null} event loop
     */
    public EventLoopThread newEventLoopThread(EventLoop eventLoop) {
        Assert.checkNotNullParam("eventLoop", eventLoop);
        EventLoopThread eventLoopThread = new EventLoopThread(this, eventLoopIdx.getAndIncrement(), eventLoop);
        eventLoopThread.start();
        return eventLoopThread;
    }

    /**
     * Construct a new instance.
     *
     * @param blockingPool the blocking pool to use (must not be {@code null})
     * @return the new scheduler (not {@code null})
     */
    public static Scheduler create(EnhancedQueueExecutor blockingPool) {
        return new Scheduler(Assert.checkNotNullParam("blockingPool", blockingPool));
    }

    /**
     * Execute the given task in a new virtual thread managed by this scheduler, initially scheduled as a worker
     * (CPU-bound) task.
     *
     * @param command the runnable task
     */
    public void execute(final Runnable command) {
        new UserThreadScheduler(this, Assert.checkNotNullParam("command", command), threadIdx.getAndIncrement()).start();
    }

    void executeOnEventLoop(final EventLoopThread eventLoopThread, final Runnable command) {
        new UserThreadScheduler(this, command, threadIdx.getAndIncrement(), eventLoopThread).start();
    }

    ThreadContainer container() {
        return container;
    }

    Dispatcher poolDispatcher() {
        return poolDispatcher;
    }

    static final class Container extends ThreadContainer {
        static final boolean DEBUG = false;
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        private Container() {
            super(true);
        }

        public void onStart(final Thread thread) {
            // todo: track shutdown
            if (DEBUG) threads.add(thread);
        }

        public void onExit(final Thread thread) {
            // todo: track shutdown
            if (DEBUG) threads.remove(thread);
        }

        public String name() {
            return "managed";
        }

        public long threadCount() {
            return threads.size();
        }

        protected boolean tryClose() {
            return super.tryClose();
        }

        public Thread owner() {
            return super.owner();
        }

        public Stream<Thread> threads() {
            return DEBUG ? threads.stream() : Stream.empty();
        }
    }

    private class PoolDispatcher extends Dispatcher {
        void execute(final UserThreadScheduler continuation) {
            blockingPool.execute(continuation);
        }

        ScheduledFuture<?> schedule(final Runnable task, final long nanos) {
            return blockingPool.schedule(task, nanos, TimeUnit.NANOSECONDS);
        }
    }
}
