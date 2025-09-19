package org.jboss.threads.virtual;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The base class for an individual thread's scheduler.
 */
abstract class ThreadScheduler implements ScheduledExecutorService, Runnable {
    private static final VarHandle yieldedHandle = ConstantBootstraps.fieldVarHandle(MethodHandles.lookup(), "yielded", VarHandle.class, ThreadScheduler.class, boolean.class);
    private static final VarHandle delayHandle = ConstantBootstraps.fieldVarHandle(MethodHandles.lookup(), "delay", VarHandle.class, ThreadScheduler.class, long.class);
    private static final VarHandle priorityHandle = ConstantBootstraps.fieldVarHandle(MethodHandles.lookup(), "priority", VarHandle.class, ThreadScheduler.class, int.class);
    /**
     * The system nanos time when this task started waiting.
     * Accessed only by carrier threads.
     */
    private long waitingSinceTime;
    /**
     * The current thread priority, between {@link Thread#MIN_PRIORITY} (lowest) or {@link Thread#MAX_PRIORITY}.
     * Accessed by carrier threads and virtual threads.
     */
    @SuppressWarnings("unused") // priorityHandle
    private int priority = Thread.NORM_PRIORITY;
    /**
     * The nanosecond delay time for scheduling.
     * Accessed by carrier threads and virtual threads.
     */
    @SuppressWarnings("unused") // delayHandle
    private long delay;
    /**
     * Indicate if this thread parked or yielded, to detect a starvation scenario.
     * Accessed by carrier threads and virtual threads.
     */
    @SuppressWarnings("unused") // yieldedHandle
    volatile boolean yielded;

    /**
     * The owning scheduler.
     */
    private final Scheduler scheduler;
    /**
     * The virtual thread associated with this task.
     */
    private final Thread virtualThread;

    ThreadScheduler(final Scheduler scheduler, final String name, final long idx) {
        this.scheduler = scheduler;
        this.virtualThread = Access.threadBuilder(this).inheritInheritableThreadLocals(false).name(name, idx).unstarted(this::runThreadBody);
    }

    void start() {
        Access.startThread(virtualThread, scheduler.container());
    }

    abstract void runThreadBody();

    void delayBy(long nanos) {
        delayHandle.setOpaque(this, nanos);
    }

    long waitingSinceTime() {
        return waitingSinceTime;
    }

    int priority() {
        return (int) priorityHandle.getOpaque(this);
    }

    void setPriority(final int priority) {
        priorityHandle.setOpaque(this, priority);
    }

    /**
     * {@return the number of nanoseconds that this thread has been waiting for}
     * The higher the waiting-since time, the higher priority a thread will have.
     * This value may be negative if the wait time includes a delay.
     *
     * @param current the current time
     */
    long waitingSince(long current) {
        long delay = (long) delayHandle.getOpaque(this);
        // delay is always 0 or positive, so result may be negative
        return Math.max(0, current - waitingSinceTime) - delay;
    }

    /**
     * Run the continuation for the current thread.
     */
    public void run() {
        assert ! Thread.currentThread().isVirtual();
        // reset delay
        delayBy(0);
        // todo: we could change the carrier thread priority when running on the pool, but it might not pay off
        try {
            Access.continuationOf(virtualThread).run();
        } finally {
            waitingSinceTime = System.nanoTime();
            yieldedHandle.setOpaque(this, true);
        }
    }

    /**
     * {@return {@code true} if this scheduler has yielded since the last invocation of this method, or {@code false} otherwise}
     */
    boolean yielded() {
        boolean yielded = (boolean) yieldedHandle.getOpaque(this);
        if (yielded) {
            yieldedHandle.setOpaque(this, false);
        }
        return yielded;
    }

    /**
     * {@return the overall scheduler for this thread scheduler}
     */
    Scheduler scheduler() {
        return scheduler;
    }

    /**
     * {@return the virtual thread associated with this thread scheduler}
     */
    Thread virtualThread() {
        return virtualThread;
    }

    /**
     * Schedule the given command, which may be the continuation for this thread.
     *
     * @param command the command (must not be {@code null})
     */
    public abstract void execute(Runnable command);

    /**
     * Schedule the given command, which is generally an unpark request for some other sleeping thread.
     *
     * @param command the command (must not be {@code null})
     */
    public abstract ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    // unimplemented methods

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    public void shutdown() {
        throw new UnsupportedOperationException();
    }

    public List<Runnable> shutdownNow() {
        throw new UnsupportedOperationException();
    }

    public boolean isShutdown() {
        throw new UnsupportedOperationException();
    }

    public boolean isTerminated() {
        throw new UnsupportedOperationException();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    public <T> Future<T> submit(Callable<T> task) {
        throw new UnsupportedOperationException();
    }

    public <T> Future<T> submit(Runnable task, T result) {
        throw new UnsupportedOperationException();
    }

    public Future<?> submit(Runnable task) {
        throw new UnsupportedOperationException();
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException();
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException();
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }
}
