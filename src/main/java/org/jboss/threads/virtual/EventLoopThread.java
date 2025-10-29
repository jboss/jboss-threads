package org.jboss.threads.virtual;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

import io.smallrye.common.annotation.Experimental;
import org.jboss.threads.JBossThread;

/**
 * An event loop carrier thread with a built-in executor.
 * To acquire the virtual thread of the event loop which is carried by this thread,
 * use {@link #virtualThread()}.
 */
public final class EventLoopThread extends JBossThread implements Executor {
    /**
     * The number of nanoseconds to offset the wait time by, per priority point.
     */
    private static final long PRIORITY_BIAS = 50_000L;
    /**
     * The virtual thread which runs the event handler.
     */
    private final EventLoopThreadScheduler elts;
    /**
     * The event loop implementation.
     */
    private final EventLoop eventLoop;
    /**
     * Comparison nanos.
     * This is not representative of the current time; rather, it's a somewhat-recent (but arbitrary)
     * sample of {@code System.nanoTime()}.
     * Update before each queue operation and this queue can run forever, as long as no task waits for more than ~138 years.
     * Such tasks might unexpectedly have a different priority.
     * But it is not likely to matter at that point.
     */
    private long cmpNanos;
    /**
     * Current nanoseconds.
     * This <em>is</em> a snapshot of the current time, taken immediately before draining the delay queue.
     */
    private long currentNanos;
    /**
     * The shared/slow task queue, which allows tasks to be enqueued from outside of this thread.
     */
    private final LinkedBlockingQueue<ThreadScheduler> sq = new LinkedBlockingQueue<>();
    /**
     * The task queue.
     * The queue is ordered by the amount of time that each entry (thread) has been waiting to run.
     */
    private final PriorityQueue<ThreadScheduler> q = new PriorityQueue<>(this::compare);
    /**
     * The bulk remover for transferring {@code sq} to {@code q}.
     */
    private final Predicate<ThreadScheduler> bulkRemover = q::add;
    /**
     * The delay queue for timed sleep (patched JDKs only).
     */
    private final DelayQueue<DelayedTask<?>> dq = new DelayQueue<>();
    /**
     * The event loop's dispatcher.
     */
    private final Dispatcher dispatcher = new EventLoopDispatcher();

    EventLoopThread(final Scheduler scheduler, final int idx, final EventLoop eventLoop) {
        super(() -> {}, "Event loop carrier thread " + idx);
        elts = new EventLoopThreadScheduler(scheduler, this, idx);
        this.eventLoop = eventLoop;
    }

    /**
     * Create and start a virtual thread which is carried by this I/O thread.
     *
     * @param runnable the body of the virtual thread (must not be {@code null})
     */
    public void execute(Runnable runnable) {
        elts.scheduler().executeOnEventLoop(this, runnable);
    }

    /**
     * {@return a new executor service which pools threads and schedules tasks to this event loop}
     * Executed tasks are free to change to another event loop or the shared pool.
     * Tasks are always started with an association to this event loop.
     */
    @Experimental("Pooled event-loop-bound threads")
    public Executor newPool() {
        return new Executor() {
            static final VarHandle taskHandle = ConstantBootstraps.fieldVarHandle(MethodHandles.lookup(), "task", VarHandle.class, Runner.class, Runnable.class);
            class Runner implements Runnable {

                private volatile Thread thread;
                @SuppressWarnings("unused") // taskHandle
                private volatile Runnable task;

                Runner(final Runnable task) {
                    this.task = task;
                }

                public void run() {
                    thread = Thread.currentThread();
                    for (;;) {
                        // get and run next task
                        Runnable task = (Runnable) taskHandle.getAndSet(this, null);
                        if (task != null) {
                            try {
                                task.run();
                            } catch (Throwable ignored) {}
                            Util.clearUnpark();
                            // re-add to queue /after/ clearing park permit
                            q.addFirst(this);
                        }
                        // wait for a new task
                        VirtualThreads.parkAndResumeOn(EventLoopThread.this);
                    }
                }
            }

            private final ConcurrentLinkedDeque<Runner> q = new ConcurrentLinkedDeque<>();

            public void execute(final Runnable command) {
                Runner runner;
                for (;;) {
                    runner = q.poll();
                    if (runner == null) {
                        // don't wait around, just start a new thread immediately
                        runner = new Runner(command);
                        EventLoopThread.this.execute(runner);
                        return;
                    }
                    // try to set the task
                    if (taskHandle.compareAndSet(runner, null, command)) {
                        LockSupport.unpark(runner.thread);
                        return;
                    }
                }
            }
        };
    }

    /**
     * {@return the owner of this event loop thread}
     */
    Scheduler owner() {
        return elts.scheduler();
    }

    /**
     * {@return the event loop for this thread}
     */
    public EventLoop eventLoop() {
        return eventLoop;
    }

    /**
     * {@return the virtual thread of the event loop itself}
     */
    public Thread virtualThread() {
        return elts.virtualThread();
    }

    /**
     * {@return the current event loop thread, or {@code null} if the current thread is not mounted on an event loop thread}
     */
    public static EventLoopThread current() {
        return Thread.currentThread().isVirtual() && Access.currentCarrier() instanceof EventLoopThread elt ? elt : null;
    }

    public void interrupt() {
        // interruption of I/O carrier threads is not allowed
    }

    /**
     * Run the carrier side of the event loop.
     * This should only be called by {@code Thread.start()}.
     *
     * @throws IllegalThreadStateException if called inappropriately
     */
    public void run() {
        if (Thread.currentThread() != this || elts.virtualThread().isAlive()) {
            throw new IllegalThreadStateException();
        }
        elts.start();
        // initialize the wait-time comparison basis
        cmpNanos = System.nanoTime();
        for (;;) {
            // drain shared queue, hopefully somewhat efficiently
            sq.removeIf(bulkRemover);
            long waitTime = -1L;
            if (!dq.isEmpty()) {
                // process the delay queue
                currentNanos = System.nanoTime();
                DelayedTask<?> dt = dq.poll();
                while (dt != null) {
                    // this will indirectly cause entries to be added to `q`
                    dt.run();
                    dt = dq.poll();
                }
                dt = dq.peek();
                if (dt != null) {
                    // set the max wait time to the amount of time before the next scheduled task
                    waitTime = Math.max(1L, dt.deadline - currentNanos);
                }
            }
            ThreadScheduler removed = q.poll();
            if (removed == null) {
                // all threads are parked, even the event loop, so just wait around
                LockSupport.park();
            } else {
                if (removed == elts) {
                    // configure the wait time
                    elts.setWaitTime(waitTime);
                }
                // update for next q operation without hitting nanoTime over and over
                cmpNanos = removed.waitingSinceTime();
                removed.run();
            }
        }
    }

    void enqueue(final ThreadScheduler continuation) {
        Thread ct = Thread.currentThread();
        if (ct == this || Access.currentCarrier() == this) {
            q.add(continuation);
        } else {
            sq.add(continuation);
            LockSupport.unpark(this);
            eventLoop().wakeup();
        }
    }

    /**
     * Compare the wait times of two tasks.
     * The task that has waited for the longest time is considered earlier than the task that has a shorter wait time.
     *
     * @param o1 the first thread scheduler (must not be {@code null})
     * @param o2 the second thread scheduler (must not be {@code null})
     * @return the comparison result
     */
    private int compare(ThreadScheduler o1, ThreadScheduler o2) {
        long cmpNanos = this.cmpNanos;

        // with priority, we have a potentially greater-than-64-bit number
        long w1 = o1.waitingSince(cmpNanos);
        int s1 = Thread.NORM_PRIORITY - o1.priority();
        w1 += s1 * PRIORITY_BIAS;

        long w2 = o2.waitingSince(cmpNanos);
        int s2 = Thread.NORM_PRIORITY - o2.priority();
        w2 += s2 * PRIORITY_BIAS;

        return Long.compare(w2, w1);
    }

    ScheduledFuture<?> schedule(final Runnable command, final long nanos) {
        // it is expected that this will only be called locally
        Thread ct = Thread.currentThread();
        assert ct == EventLoopThread.this;
        DelayedTask<Object> dt = new DelayedTask<>(command, System.nanoTime() + nanos);
        dq.add(dt);
        return dt;
    }

    Dispatcher dispatcher() {
        return dispatcher;
    }

    private final class DelayedTask<V> implements ScheduledFuture<V>, Runnable {
        private final Runnable task;
        private final long deadline;

        private DelayedTask(final Runnable task, final long deadline) {
            this.task = task;
            this.deadline = deadline;
        }

        public long getDelay(final TimeUnit unit) {
            long currentNanos = EventLoopThread.this.currentNanos;
            return unit.convert(deadline - currentNanos, TimeUnit.NANOSECONDS);
        }

        public int compareTo(final Delayed o) {
            if (o instanceof DelayedTask<?> dt) {
                long currentNanos = EventLoopThread.this.currentNanos;
                return Long.compare(deadline - currentNanos, dt.deadline - currentNanos);
            } else {
                throw new IllegalStateException();
            }
        }

        public boolean cancel(final boolean mayInterruptIfRunning) {
            Thread ct = Thread.currentThread();
            if (ct == EventLoopThread.this || ct.isVirtual() && Access.currentCarrier() == EventLoopThread.this) {
                return dq.remove(this);
            }
            // else unsupported
            return false;
        }

        public boolean isCancelled() {
            // unsupported
            return false;
        }

        public boolean isDone() {
            // unsupported
            return false;
        }

        public V get() {
            throw new UnsupportedOperationException();
        }

        public V get(final long timeout, final TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        public void run() {
            task.run();
        }
    }

    private class EventLoopDispatcher extends Dispatcher {
        void execute(final UserThreadScheduler continuation) {
            enqueue(continuation);
        }

        ScheduledFuture<?> schedule(final Runnable task, final long nanos) {
            return EventLoopThread.this.schedule(task, nanos);
        }
    }
}

