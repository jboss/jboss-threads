/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.threads;

import static java.lang.Math.max;
import static java.util.concurrent.locks.LockSupport.*;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.LongAdder;

import org.wildfly.common.Assert;
import org.wildfly.common.annotation.NotNull;
import sun.misc.Contended;

/**
 * A task-or-thread queue backed thread pool executor service.  Tasks are added in a FIFO manner, and consumers in a LIFO manner.
 * Threads are only ever created in the event that there are no idle threads available to service a task, which, when
 * combined with the LIFO-based consumer behavior, means that the thread pool will generally trend towards the minimum
 * necessary size.  In addition, the optional {@linkplain #setGrowthResistance(float) growth resistance feature} can
 * be used to further govern the thread pool size.
 * <p>
 * New instances of this thread pool are created by constructing and configuring a {@link Builder} instance, and calling
 * its {@link Builder#build() build()} method.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@Contended
public final class EnhancedQueueExecutor extends AbstractExecutorService {

    /*
    ┌──────────────────────────┐
    │ Explanation of operation │
    └──────────────────────────┘

    The primary mechanism of this executor is the special linked list/stack.  This list has the following properties:
      • Multiple node types:
        ◦ Task nodes (TaskNode), which have the following properties:
          ▪ Strongly connected (no task is ever removed from the middle of the list; tail can always be found by following .next pointers)
          ▪ FIFO (insertion at tail.next, "removal" at head.next)
          ▪ Head and tail always refer to TaskNodes; head.next/tail.next are the "true" action points for all node types
          ▪ At least one dead task node is always in the list, thus the task is cleared after execution to avoid leaks
        ◦ Waiting consumer thread nodes (PoolThreadNode), which have the following properties:
          ▪ LIFO/FILO (insertion and removal at tail.next)
          ▪ Carrier for task handoff between producer and waiting consumer
        ◦ Waiting termination (awaitTermination) thread nodes (TerminateWaiterNode), which have the following properties:
          ▪ Append-only (insertion at tail.next.next...next)
          ▪ All removed at once when termination is complete
          ▪ If a thread stops waiting, the node remains (but its thread field is cleared to prevent (best-effort) useless unparks)
          ▪ Once cleared, the thread field can never be reinitialized
      • Concurrently accessed (multiple threads may read the list and update the head and tail pointers)
      • TaskNode.next may be any node type or null
      • PoolThreadNode.next may only be another PoolThreadNode or null
      • TerminateWaiterNode.next may only be another TerminateWaiterNode or null

    The secondary mechanism is the thread status atomic variable.  It is logically a structure with the following fields:
      • Current thread count (currentSizeOf(), withCurrentSize())
      • Core pool size (coreSizeOf(), withCoreSize())
      • Max pool size (maxSizeOf(), withMaxSize())
      • Shutdown-requested flag (isShutdownRequested(), withShutdownRequested())
      • Shutdown-with-interrupt requested flag (isShutdownInterrupt(), withShutdownInterrupt())
      • Shutdown-completed flag (isShutdownComplete(), withShutdownComplete())
      • The decision to create a new thread is affected by whether the number of active threads is less than the core size;
        if not, then the growth resistance factor is applied to decide whether the task should be enqueued or a new thread begun.
        Note: the default growth resistance factor is 0% resistance.

    The final mechanism is the queue status atomic variable.  It is logically a structure with the following fields:
      • Current queue size (currentQueueSizeOf(), withCurrentQueueSize())
      • Queue size limit (maxQueueSizeOf(), withMaxQueueSize())

     */

    // =======================================================
    // Optimization control flags
    // =======================================================

    /**
     * A global hint which establishes whether it is recommended to disable uses of {@code EnhancedQueueExecutor}.
     * This hint defaults to {@code false} but can be changed to {@code true} by setting the {@code jboss.threads.eqe.disable}
     * property to {@code true} before this class is initialized.
     */
    public static final boolean DISABLE_HINT = readBooleanProperty("disable", false);

    /**
     * Update the tail pointer opportunistically.
     */
    static final boolean UPDATE_TAIL = readBooleanProperty("update-tail", false);
    /**
     * Update the summary statistics.
     */
    static final boolean UPDATE_STATISTICS = readBooleanProperty("statistics", true);
    /**
     * Suppress queue limit and size tracking for performance.
     */
    static final boolean NO_QUEUE_LIMIT = readBooleanProperty("unlimited-queue", false);
    /**
     * Yield the thread under contention.
     */
    static final boolean SPIN_YIELD = readBooleanProperty("spin-yield", false);
    /**
     * Attempt to lock frequently-contended operations on the list tail.
     */
    static final boolean TAIL_LOCK = readBooleanProperty("tail-lock", false);

    // =======================================================
    // Constants
    // =======================================================

    static final Executor DEFAULT_HANDLER = JBossExecutors.rejectingExecutor();

    // =======================================================
    // Locks
    // =======================================================

    /**
     * The tail lock.  Only used if {@link #TAIL_LOCK} is {@code true}.
     */
    final Object tailLock = new Object();

    // =======================================================
    // Immutable configuration fields
    // =======================================================

    /**
     * The thread factory.
     */
    private final ThreadFactory threadFactory;
    /**
     * The termination task to execute when the thread pool exits.
     */
    private final Runnable terminationTask;
    /**
     * The approximate set of pooled threads.
     */
    private final Set<Thread> runningThreads = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // =======================================================
    // Current state fields
    // =======================================================

    /**
     * The node <em>preceding</em> the head node; this field is not {@code null}.  This is
     * the removal point for tasks (and the insertion point for waiting threads).
     */
    @NotNull
    @SuppressWarnings("unused") // used by field updater
    volatile TaskNode head;

    /**
     * The node <em>preceding</em> the tail node; this field is not {@code null}.  This
     * is the insertion point for tasks (and the removal point for waiting threads).
     */
    @NotNull
    @SuppressWarnings("unused") // used by field updater
    volatile TaskNode tail;

    /**
     * Queue size:
     * <ul>
     *     <li>Bit 00..1F: current queue length</li>
     *     <li>Bit 20..3F: queue limit</li>
     * </ul>
     */
    @SuppressWarnings("unused") // used by field updater
    volatile long queueSize;

    /**
     * Active consumers:
     * <ul>
     *     <li>Bit 00..19: current number of running threads</li>
     *     <li>Bit 20..39: core pool size</li>
     *     <li>Bit 40..59: maximum pool size</li>
     *     <li>Bit 60: 1 = allow core thread timeout; 0 = disallow core thread timeout</li>
     *     <li>Bit 61: 1 = shutdown requested; 0 = shutdown not requested</li>
     *     <li>Bit 62: 1 = shutdown task interrupt requested; 0 = interrupt not requested</li>
     *     <li>Bit 63: 1 = shutdown complete; 0 = shutdown not complete</li>
     * </ul>
     */
    @SuppressWarnings("unused") // used by field updater
    volatile long threadStatus;

    /**
     * The thread keep-alive timeout value.
     */
    volatile long timeoutNanos;

    /**
     * A resistance factor applied after the core pool is full; values applied here will cause that fraction
     * of submissions to create new threads when no idle thread is available.   A value of {@code 0.0f} implies that
     * threads beyond the core size should be created as aggressively as threads within it; a value of {@code 1.0f}
     * implies that threads beyond the core size should never be created.
     */
    volatile float growthResistance;

    /**
     * The handler for tasks which cannot be accepted by the executor.
     */
    volatile Executor handoffExecutor = DEFAULT_HANDLER;

    /**
     * The handler for uncaught exceptions which occur during user tasks.
     */
    volatile Thread.UncaughtExceptionHandler exceptionHandler = JBossExecutors.loggingExceptionHandler();

    // =======================================================
    // Statistics fields and counters
    // =======================================================

    /**
     * The peak number of threads ever created by this pool.
     */
    @SuppressWarnings("unused") // used by field updater
    volatile int peakThreadCount;
    /**
     * The approximate peak queue size.
     */
    @SuppressWarnings("unused") // used by field updater
    volatile int peakQueueSize;

    private final LongAdder submittedTaskCounter = new LongAdder();
    private final LongAdder completedTaskCounter = new LongAdder();
    private final LongAdder rejectedTaskCounter = new LongAdder();

    /**
     * The current active number of threads.
     */
    @SuppressWarnings("unused")
    volatile int activeCount;

    // =======================================================
    // Updaters
    // =======================================================

    private static final AtomicReferenceFieldUpdater<EnhancedQueueExecutor, TaskNode> headUpdater = AtomicReferenceFieldUpdater.newUpdater(EnhancedQueueExecutor.class, TaskNode.class, "head");
    private static final AtomicReferenceFieldUpdater<EnhancedQueueExecutor, TaskNode> tailUpdater = AtomicReferenceFieldUpdater.newUpdater(EnhancedQueueExecutor.class, TaskNode.class, "tail");

    private static final AtomicLongFieldUpdater<EnhancedQueueExecutor> queueSizeUpdater = AtomicLongFieldUpdater.newUpdater(EnhancedQueueExecutor.class, "queueSize");
    private static final AtomicLongFieldUpdater<EnhancedQueueExecutor> threadStatusUpdater = AtomicLongFieldUpdater.newUpdater(EnhancedQueueExecutor.class, "threadStatus");

    private static final AtomicIntegerFieldUpdater<EnhancedQueueExecutor> peakThreadCountUpdater = AtomicIntegerFieldUpdater.newUpdater(EnhancedQueueExecutor.class, "peakThreadCount");
    private static final AtomicIntegerFieldUpdater<EnhancedQueueExecutor> activeCountUpdater = AtomicIntegerFieldUpdater.newUpdater(EnhancedQueueExecutor.class, "activeCount");
    private static final AtomicIntegerFieldUpdater<EnhancedQueueExecutor> peakQueueSizeUpdater = AtomicIntegerFieldUpdater.newUpdater(EnhancedQueueExecutor.class, "peakQueueSize");

    // =======================================================
    // Thread state field constants
    // =======================================================

    private static final long TS_THREAD_CNT_MASK = 0b1111_1111_1111_1111_1111L; // 20 bits, can be shifted as needed

    // shift amounts
    private static final long TS_CURRENT_SHIFT   = 0;
    private static final long TS_CORE_SHIFT      = 20;
    private static final long TS_MAX_SHIFT       = 40;

    private static final long TS_ALLOW_CORE_TIMEOUT = 1L << 60;
    private static final long TS_SHUTDOWN_REQUESTED = 1L << 61;
    private static final long TS_SHUTDOWN_INTERRUPT = 1L << 62;
    @SuppressWarnings("NumericOverflow")
    private static final long TS_SHUTDOWN_COMPLETE = 1L << 63;

    // =======================================================
    // Marker objects
    // =======================================================

    static final QNode TERMINATE_COMPLETE = new TerminateWaiterNode(null);

    static final Runnable WAITING = new NullRunnable();
    static final Runnable GAVE_UP = new NullRunnable();
    static final Runnable ACCEPTED = new NullRunnable();
    static final Runnable EXIT = new NullRunnable();

    // =======================================================
    // Constructor
    // =======================================================

    EnhancedQueueExecutor(final Builder builder) {
        int maxSize = builder.getMaximumPoolSize();
        int coreSize = Math.min(builder.getCorePoolSize(), maxSize);
        this.threadFactory = builder.getThreadFactory();
        this.terminationTask = builder.getTerminationTask();
        this.growthResistance = builder.getGrowthResistance();
        final long keepAliveTime = builder.getKeepAliveTime(TimeUnit.NANOSECONDS);
        // initial dead node
        head = tail = new TaskNode(null, completedTaskCounter);
        // thread stat
        threadStatus = withCoreSize(withMaxSize(withAllowCoreTimeout(0L, builder.allowsCoreThreadTimeOut()), maxSize), coreSize);
        timeoutNanos = max(1L, keepAliveTime);
        queueSize = withMaxQueueSize(withCurrentQueueSize(0L, 0), builder.getMaximumQueueSize());
    }

    // =======================================================
    // Builder
    // =======================================================

    /**
     * The builder class for an {@code EnhancedQueueExecutor}.  All the fields are initialized to sensible defaults for
     * a small thread pool.
     */
    public static final class Builder {
        private ThreadFactory threadFactory = Executors.defaultThreadFactory();
        private Runnable terminationTask = NullRunnable.getInstance();
        private int coreSize = 16;
        private int maxSize = 64;
        private long keepAliveTime = 30;
        private TimeUnit keepAliveUnits = TimeUnit.SECONDS;
        private float growthResistance;
        private boolean allowCoreTimeOut;
        private int maxQueueSize = Integer.MAX_VALUE;

        /**
         * Construct a new instance.
         */
        public Builder() {}

        /**
         * Get the configured thread factory.
         *
         * @return the configured thread factory (not {@code null})
         */
        public ThreadFactory getThreadFactory() {
            return threadFactory;
        }

        /**
         * Set the configured thread factory.
         *
         * @param threadFactory the configured thread factory (must not be {@code null})
         * @return this builder
         */
        public Builder setThreadFactory(final ThreadFactory threadFactory) {
            Assert.checkNotNullParam("threadFactory", threadFactory);
            this.threadFactory = threadFactory;
            return this;
        }

        /**
         * Get the termination task.  By default, an empty {@code Runnable} is used.
         *
         * @return the termination task (not {@code null})
         */
        public Runnable getTerminationTask() {
            return terminationTask;
        }

        /**
         * Set the termination task.
         *
         * @param terminationTask the termination task (must not be {@code null})
         * @return this builder
         */
        public Builder setTerminationTask(final Runnable terminationTask) {
            Assert.checkNotNullParam("terminationTask", terminationTask);
            this.terminationTask = terminationTask;
            return this;
        }

        /**
         * Get the core pool size.  This is the size below which new threads will always be created if no idle threads
         * are available.  If the pool size reaches the core size but has not yet reached the maximum size, a resistance
         * factor will be applied to each task submission which determines whether the task should be queued or a new
         * thread started.
         *
         * @return the core pool size
         * @see EnhancedQueueExecutor#getCorePoolSize()
         */
        public int getCorePoolSize() {
            return coreSize;
        }

        /**
         * Set the core pool size.  If the configured maximum pool size is less than the configured core size, the
         * core size will be reduced to match the maximum size when the thread pool is constructed.
         *
         * @param coreSize the core pool size (must be greater than or equal to 0, and less than 2^20)
         * @return this builder
         * @see EnhancedQueueExecutor#setCorePoolSize(int)
         */
        public Builder setCorePoolSize(final int coreSize) {
            Assert.checkMinimumParameter("coreSize", 0, coreSize);
            Assert.checkMaximumParameter("coreSize", TS_THREAD_CNT_MASK, coreSize);
            this.coreSize = coreSize;
            return this;
        }

        /**
         * Get the maximum pool size.  This is the absolute upper limit to the size of the thread pool.
         *
         * @return the maximum pool size
         * @see EnhancedQueueExecutor#getMaximumPoolSize()
         */
        public int getMaximumPoolSize() {
            return maxSize;
        }

        /**
         * Set the maximum pool size.  If the configured maximum pool size is less than the configured core size, the
         * core size will be reduced to match the maximum size when the thread pool is constructed.
         *
         * @param maxSize the maximum pool size (must be greater than or equal to 0, and less than 2^20)
         * @return this builder
         * @see EnhancedQueueExecutor#setMaximumPoolSize(int)
         */
        public Builder setMaximumPoolSize(final int maxSize) {
            Assert.checkMinimumParameter("maxSize", 0, maxSize);
            Assert.checkMaximumParameter("maxSize", TS_THREAD_CNT_MASK, maxSize);
            this.maxSize = maxSize;
            return this;
        }

        /**
         * Get the thread keep-alive time.  This is the amount of time (in the configured time unit) that idle threads
         * will wait for a task before exiting.
         *
         * @param keepAliveUnits the time keepAliveUnits of the keep-alive time (must not be {@code null})
         * @return the thread keep-alive time
         * @see EnhancedQueueExecutor#getKeepAliveTime(TimeUnit)
         */
        public long getKeepAliveTime(TimeUnit keepAliveUnits) {
            Assert.checkNotNullParam("keepAliveUnits", keepAliveUnits);
            return keepAliveUnits.convert(keepAliveTime, this.keepAliveUnits);
        }

        /**
         * Set the thread keep-alive time.
         *
         * @param keepAliveTime the thread keep-alive time (must be greater than 0)
         * @param keepAliveUnits the time keepAliveUnits of the keep-alive time (must not be {@code null})
         * @return this builder
         * @see EnhancedQueueExecutor#setKeepAliveTime(long, TimeUnit)
         */
        public Builder setKeepAliveTime(final long keepAliveTime, final TimeUnit keepAliveUnits) {
            Assert.checkMinimumParameter("keepAliveTime", 1L, keepAliveTime);
            Assert.checkNotNullParam("keepAliveUnits", keepAliveUnits);
            this.keepAliveTime = keepAliveTime;
            this.keepAliveUnits = keepAliveUnits;
            return this;
        }

        /**
         * Get the thread pool growth resistance.  This is the average fraction of submitted tasks that will be enqueued (instead
         * of causing a new thread to start) when there are no idle threads and the pool size is equal to or greater than
         * the core size (but still less than the maximum size).  A value of {@code 0.0} indicates that tasks should
         * not be enqueued until the pool is completely full; a value of {@code 1.0} indicates that tasks should always
         * be enqueued until the queue is completely full.
         *
         * @return the thread pool growth resistance
         * @see EnhancedQueueExecutor#getGrowthResistance()
         */
        public float getGrowthResistance() {
            return growthResistance;
        }

        /**
         * Set the thread pool growth resistance.
         *
         * @param growthResistance the thread pool growth resistance (must be in the range {@code 0.0f ≤ n ≤ 1.0f})
         * @return this builder
         * @see #getGrowthResistance()
         * @see EnhancedQueueExecutor#setGrowthResistance(float)
         */
        public Builder setGrowthResistance(final float growthResistance) {
            Assert.checkMinimumParameter("growthResistance", 0.0f, growthResistance);
            Assert.checkMaximumParameter("growthResistance", 1.0f, growthResistance);
            this.growthResistance = growthResistance;
            return this;
        }

        /**
         * Determine whether core threads are allowed to time out.  A "core thread" is defined as any thread in the pool
         * when the pool size is below the pool's {@linkplain #getCorePoolSize() core pool size}.
         *
         * @return {@code true} if core threads are allowed to time out, {@code false} otherwise
         * @see EnhancedQueueExecutor#allowsCoreThreadTimeOut()
         */
        public boolean allowsCoreThreadTimeOut() {
            return allowCoreTimeOut;
        }

        /**
         * Establish whether core threads are allowed to time out.  A "core thread" is defined as any thread in the pool
         * when the pool size is below the pool's {@linkplain #getCorePoolSize() core pool size}.
         *
         * @param value {@code true} if core threads are allowed to time out, {@code false} otherwise
         * @return this builder
         * @see EnhancedQueueExecutor#allowCoreThreadTimeOut(boolean)
         */
        public Builder allowCoreThreadTimeOut(final boolean allowCoreTimeOut) {
            this.allowCoreTimeOut = allowCoreTimeOut;
            return this;
        }

        /**
         * Get the maximum queue size.  If the queue is full and a task cannot be immediately accepted, rejection will result.
         *
         * @return the maximum queue size
         * @see EnhancedQueueExecutor#getMaximumQueueSize()
         */
        public int getMaximumQueueSize() {
            return maxQueueSize;
        }

        /**
         * Set the maximum queue size.
         *
         * @param maxQueueSize the maximum queue size (must be ≥ 0)
         * @see EnhancedQueueExecutor#setMaximumQueueSize(int)
         */
        public Builder setMaximumQueueSize(final int maxQueueSize) {
            Assert.checkMinimumParameter("maxQueueSize", 0, maxQueueSize);
            Assert.checkMaximumParameter("maxQueueSize", Integer.MAX_VALUE, maxQueueSize);
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        /**
         * Construct the executor from the configured parameters.
         *
         * @return the executor, which will be active and ready to accept tasks (not {@code null})
         */
        public EnhancedQueueExecutor build() {
            return new EnhancedQueueExecutor(this);
        }
    }

    // =======================================================
    // ExecutorService
    // =======================================================

    /**
     * Execute a task.
     *
     * @param runnable the task to execute (must not be {@code null})
     */
    public void execute(Runnable runnable) {
        if (TAIL_LOCK && ! Thread.holdsLock(tailLock)) synchronized (tailLock) {
            execute(runnable);
            return;
        }
        Assert.checkNotNullParam("runnable", runnable);
        TaskNode tail = this.tail;
        QNode tailNext;
        for (;;) {
            tailNext = tail.getNext();
            if (tailNext instanceof TaskNode) {
                TaskNode tailNextTaskNode;
                do {
                    tailNextTaskNode = (TaskNode) tailNext;
                    // retry
                    tail = tailNextTaskNode;
                    tailNext = tail.getNext();
                } while (tailNext instanceof TaskNode);
                // opportunistically update for the possible benefit of other threads
                if (UPDATE_TAIL) compareAndSetTail(tail, tailNextTaskNode);
            }
            // we've progressed to the first non-task node, as far as we can see
            assert ! (tailNext instanceof TaskNode);
            if (tailNext instanceof PoolThreadNode) {
                final QNode tailNextNext = tailNext.getNext();
                // state change ex1:
                //   tail(snapshot).next ← tail(snapshot).next(snapshot).next(snapshot)
                // succeeds: -
                // cannot succeed: sh2
                // preconditions:
                //   tail(snapshot) is a dead TaskNode
                //   tail(snapshot).next is PoolThreadNode
                //   tail(snapshot).next.next* is PoolThreadNode or null
                // additional success postconditions: -
                // failure postconditions: -
                // post-actions (succeed):
                //   run state change ex2
                // post-actions (fail):
                //   retry with new tail(snapshot)
                if (tail.compareAndSetNext(tailNext, tailNextNext)) {
                    PoolThreadNode consumerNode = (PoolThreadNode) tailNext;
                    // state change ex2:
                    //   tail(snapshot).next(snapshot).task ← runnable
                    // succeeds: ex1
                    // preconditions:
                    //   tail(snapshot).next(snapshot).task = WAITING
                    // post-actions (succeed):
                    //   unpark thread and return
                    // post-actions (fail):
                    //   retry outer with new tail(snapshot)
                    if (consumerNode.compareAndSetTask(WAITING, runnable)) {
                        if (UPDATE_STATISTICS) submittedTaskCounter.increment();
                        unpark(consumerNode.getThread());
                        return;
                    }
                    // otherwise the consumer gave up or was exited already, so fall out and...
                }
                // retry with new tail(snapshot) as was foretold
                tail = this.tail;
            } else if (tailNext == null) {
                // no consumers available; maybe we can start one
                if (tryCreateThreadForTask(runnable, growthResistance)) {
                    // submitted!
                    return;
                }
                // no; try to enqueue
                if (! NO_QUEUE_LIMIT && ! increaseQueueSize()) {
                    // queue is full
                    // OK last effort to create a thread, disregarding growth limit
                    if (tryCreateThreadForTask(runnable, 0.0f)) {
                        // success!
                        return;
                    }
                    if (UPDATE_STATISTICS) rejectedTaskCounter.increment();
                    rejectQueueFull(runnable);
                    return;
                }
                // queue size increased successfully; we can add to the list
                TaskNode node = new TaskNode(runnable, completedTaskCounter);
                // state change ex5:
                //   tail(snapshot).next ← new task node
                // cannot succeed: sh2
                // preconditions:
                //   tail(snapshot).next = null
                //   ex3 failed precondition
                //   queue size increased to accommodate node
                // postconditions (success):
                //   tail(snapshot).next = new task node
                if (tail.compareAndSetNext(null, node)) {
                    // try to update tail to the new node; if this CAS fails then tail already points at past the node
                    // this is because tail can only ever move forward, and the task list is always strongly connected
                    compareAndSetTail(tail, node);
                    if (UPDATE_STATISTICS) submittedTaskCounter.increment();
                    // last check to ensure that there is at least one existent thread to avoid rare thread timeout race condition
                    if (currentSizeOf(threadStatus) == 0) tryCreateThreadForTask(null, 0.0f);
                    return;
                }
                // we failed; we have to drop the queue size back down again to compensate before we can retry
                if (! NO_QUEUE_LIMIT) decreaseQueueSize();
                // retry with new tail(snapshot)
                tail = this.tail;
            } else {
                // no consumers are waiting and the tail(snapshot).next node is non-null and not a task node, therefore it must be a...
                assert tailNext instanceof TerminateWaiterNode;
                // shutting down
                if (UPDATE_STATISTICS) rejectedTaskCounter.increment();
                rejectShutdown(runnable);
                return;
            }
            if (SPIN_YIELD) Thread.yield();
        }
    }

    /**
     * Request that shutdown be initiated for this thread pool.  This is equivalent to calling
     * {@link #shutdown(boolean) shutdown(false)}; see that method for more information.
     */
    public void shutdown() {
        shutdown(false);
    }

    /**
     * Attempt to stop the thread pool immediately by interrupting all running threads and de-queueing all pending
     * tasks.  The thread pool might not be fully stopped when this method returns, if a currently running task
     * does not respect interruption.
     *
     * @return a list of pending tasks (not {@code null})
     */
    public List<Runnable> shutdownNow() {
        shutdown(true);
        final ArrayList<Runnable> list = new ArrayList<>();
        TaskNode head = this.head;
        QNode headNext;
        for (;;) {
            headNext = head.getNext();
            if (headNext instanceof TaskNode) {
                TaskNode taskNode = (TaskNode) headNext;
                if (compareAndSetHead(head, taskNode)) {
                    if (! NO_QUEUE_LIMIT) decreaseQueueSize();
                    head = taskNode;
                    list.add(taskNode.task);
                }
                // retry
            } else {
                // no more tasks;
                return list;
            }
        }
    }

    /**
     * Determine whether shutdown was requested on this thread pool.
     *
     * @return {@code true} if shutdown was requested, {@code false} otherwise
     */
    public boolean isShutdown() {
        return isShutdownRequested(threadStatus);
    }

    /**
     * Determine whether shutdown has completed on this thread pool.
     *
     * @return {@code true} if shutdown has completed, {@code false} otherwise
     */
    public boolean isTerminated() {
        return isShutdownComplete(threadStatus);
    }

    /**
     * Wait for the thread pool to complete termination.  If the timeout expires before the thread pool is terminated,
     * {@code false} is returned.  If the calling thread is interrupted before the thread pool is terminated, then
     * an {@link InterruptedException} is thrown.
     *
     * @param timeout the amount of time to wait (must be ≥ 0)
     * @param unit the unit of time to use for waiting (must not be {@code null})
     * @return {@code true} if the thread pool terminated within the given timeout, {@code false} otherwise
     * @throws InterruptedException if the calling thread was interrupted before either the time period elapsed or the pool terminated successfully
     */
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        Assert.checkMinimumParameter("timeout", 0, timeout);
        Assert.checkNotNullParam("unit", unit);
        if (timeout > 0) {
            final TerminateWaiterNode node = new TerminateWaiterNode(Thread.currentThread());
            // stick it on the queue
            QNode tail = this.tail;
            QNode tailNext;
            for (;;) {
                tailNext = tail.getNext();
                if (tailNext == null) {
                    if (tail.compareAndSetNext(null, node)) {
                        // now we wait!
                        break;
                    }
                } else if (tailNext == TERMINATE_COMPLETE) {
                    // nothing more to be done!
                    return true;
                } else {
                    if (UPDATE_TAIL && tailNext instanceof TaskNode) {
                        assert tail instanceof TaskNode; // else tailNext couldn't possibly be a TaskNode
                        compareAndSetTail(((TaskNode) tail), ((TaskNode) tailNext));
                    }
                    tail = tailNext;
                }
            }
            try {
                parkNanos(this, unit.toNanos(timeout));
            } finally {
                // prevent future spurious unparks without sabotaging the queue's integrity
                node.getAndClearThread();
            }
        }
        if (Thread.interrupted()) throw new InterruptedException();
        return isTerminated();
    }

    // =======================================================
    // Other public API
    // =======================================================

    /**
     * Initiate shutdown of this thread pool.  After this method is called, no new tasks will be accepted.  Once
     * the last task is complete, the thread pool will be terminated and its
     * {@linkplain Builder#setTerminationTask(Runnable) termination task}
     * will be called.  Calling this method more than once has no additional effect, unless all previous calls
     * had the {@code interrupt} parameter set to {@code false} and the subsequent call sets it to {@code true}, in
     * which case all threads in the pool will be interrupted.
     *
     * @param interrupt {@code true} to request that currently-running tasks be interrupted; {@code false} otherwise
     */
    public void shutdown(boolean interrupt) {
        long oldStatus, newStatus;
        // state change sh1:
        //   threadStatus ← threadStatus | shutdown
        // succeeds: -
        // preconditions:
        //   none (thread status may be in any state)
        // postconditions (succeed):
        //   (always) ShutdownRequested set in threadStatus
        //   (maybe) ShutdownInterrupted set in threadStatus (depends on parameter)
        //   (maybe) ShutdownComplete set in threadStatus (if currentSizeOf() == 0)
        //   (maybe) exit if no change
        // postconditions (fail): -
        // post-actions (fail):
        //   repeat state change until success or return
        do {
            oldStatus = threadStatus;
            newStatus = withShutdownRequested(oldStatus);
            if (interrupt) newStatus = withShutdownInterrupt(newStatus);
            if (currentSizeOf(oldStatus) == 0) newStatus = withShutdownComplete(newStatus);
            if (newStatus == oldStatus) return;
        } while (! compareAndSetThreadStatus(oldStatus, newStatus));
        assert oldStatus != newStatus;
        if (isShutdownRequested(newStatus) != isShutdownRequested(oldStatus)) {
            assert ! isShutdownRequested(oldStatus); // because it can only ever be set, not cleared
            // we initiated shutdown
            // clear out all consumers and append a dummy waiter node
            TaskNode tail = this.tail;
            QNode tailNext;
            // a marker to indicate that termination was requested
            final TerminateWaiterNode terminateNode = new TerminateWaiterNode(null);
            for (;;) {
                tailNext = tail.getNext();
                if (tailNext == tail) {
                    // we raced with shutdownNow, and now we're in a pickle! re-get tail & retry
                    tail = getTail();
                } else if (tailNext instanceof TaskNode) {
                    tail = (TaskNode) tailNext;
                } else if (tailNext instanceof PoolThreadNode || tailNext == null) {
                    // remove the entire chain from this point
                    PoolThreadNode node = (PoolThreadNode) tailNext;
                    // state change sh2:
                    //   tail.next ← terminateNode(null)
                    // succeeds: sh1
                    // preconditions:
                    //   threadStatus contains shutdown
                    //   tail(snapshot) is a task node
                    //   tail(snapshot).next is a (list of) pool thread node(s) or null
                    // postconditions (succeed):
                    //   tail(snapshot).next is terminateNode(null)
                    if (tail.compareAndSetNext(node, terminateNode)) {
                        // got it!
                        // state change sh3:
                        //   node.task ← EXIT
                        // preconditions:
                        //   none (node task may be in any state)
                        // postconditions (succeed):
                        //   task is EXIT
                        // postconditions (fail):
                        //   no change (repeat loop)
                        while (node != null) {
                            node.compareAndSetTask(WAITING, EXIT);
                            unpark(node.thread);
                            node = node.getNext();
                        }
                        // success; exit loop
                        break;
                    }
                    // repeat loop (failed CAS)
                } else if (tailNext instanceof TerminateWaiterNode) {
                    // theoretically impossible, but it means we have nothing else to do
                    break;
                } else {
                    throw Assert.unreachableCode();
                }
            }
        }
        if (isShutdownInterrupt(newStatus) != isShutdownInterrupt(oldStatus)) {
            assert ! isShutdownInterrupt(oldStatus); // because it can only ever be set, not cleared
            // we initiated interrupt, so interrupt all currently active threads
            for (Thread thread : runningThreads) {
                thread.interrupt();
            }
        }
        if (isShutdownComplete(newStatus) != isShutdownComplete(oldStatus)) {
            assert ! isShutdownComplete(oldStatus);  // because it can only ever be set, not cleared
            completeTermination();
        }
    }

    /**
     * Determine if this thread pool is in the process of terminating but has not yet completed.
     *
     * @return {@code true} if the thread pool is terminating, or {@code false} if the thread pool is not terminating or has completed termination
     */
    public boolean isTerminating() {
        final long threadStatus = this.threadStatus;
        return isShutdownRequested(threadStatus) && ! isShutdownComplete(threadStatus);
    }

    /**
     * Start an idle core thread.  Normally core threads are only begun when a task was submitted to the executor
     * but no thread is immediately available to handle the task.
     *
     * @return {@code true} if a core thread was started, or {@code false} if all core threads were already running or
     *   if the thread failed to be created for some other reason
     */
    public boolean prestartCoreThread() {
        try {
            return tryCreateThreadForTask(null, 1.0f);
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    /**
     * Start all core threads.  Calls {@link #prestartCoreThread()} in a loop until it returns {@code false}.
     *
     * @return the number of core threads created
     */
    public int prestartAllCoreThreads() {
        int cnt = 0;
        while (prestartCoreThread()) cnt ++;
        return cnt;
    }

    // =======================================================
    // Tuning & configuration parameters (run time)
    // =======================================================

    /**
     * Get the thread pool growth resistance.  This is the average fraction of submitted tasks that will be enqueued (instead
     * of causing a new thread to start) when there are no idle threads and the pool size is equal to or greater than
     * the core size (but still less than the maximum size).  A value of {@code 0.0} indicates that tasks should
     * not be enqueued until the pool is completely full; a value of {@code 1.0} indicates that tasks should always
     * be enqueued until the queue is completely full.
     *
     * @return the configured growth resistance factor
     * @see Builder#getGrowthResistance() Builder.getGrowthResistance()
     */
    public float getGrowthResistance() {
        return growthResistance;
    }

    /**
     * Set the growth resistance factor.
     *
     * @param growthResistance the thread pool growth resistance (must be in the range {@code 0.0f ≤ n ≤ 1.0f})
     * @see Builder#setGrowthResistance(float) Builder.setGrowthResistance()
     */
    public void setGrowthResistance(final float growthResistance) {
        Assert.checkMinimumParameter("growthResistance", 0.0f, growthResistance);
        Assert.checkMaximumParameter("growthResistance", 1.0f, growthResistance);
        this.growthResistance = growthResistance;
    }

    /**
     * Get the core pool size.  This is the size below which new threads will always be created if no idle threads
     * are available.  If the pool size reaches the core size but has not yet reached the maximum size, a resistance
     * factor will be applied to each task submission which determines whether the task should be queued or a new
     * thread started.
     *
     * @return the core pool size
     * @see Builder#getCorePoolSize() Builder.getCorePoolSize()
     */
    public int getCorePoolSize() {
        return coreSizeOf(threadStatus);
    }

    /**
     * Set the core pool size.  If the configured maximum pool size is less than the configured core size, the
     * core size will be reduced to match the maximum size when the thread pool is constructed.
     *
     * @param coreSize the core pool size (must be greater than or equal to 0, and less than 2^20)
     * @see Builder#setCorePoolSize(int) Builder.setCorePoolSize()
     */
    public void setCorePoolSize(final int corePoolSize) {
        Assert.checkMinimumParameter("corePoolSize", 0, corePoolSize);
        Assert.checkMaximumParameter("corePoolSize", TS_THREAD_CNT_MASK, corePoolSize);
        long oldVal, newVal;
        do {
            oldVal = threadStatus;
            if (corePoolSize > maxSizeOf(oldVal)) {
                // automatically bump up max size to match
                newVal = withCoreSize(withMaxSize(oldVal, corePoolSize), corePoolSize);
            } else {
                newVal = withCoreSize(oldVal, corePoolSize);
            }
        } while (! compareAndSetThreadStatus(oldVal, newVal));
        if (maxSizeOf(newVal) < maxSizeOf(oldVal) || coreSizeOf(newVal) < coreSizeOf(oldVal)) {
            // poke all the threads to try to terminate any excess eagerly
            for (Thread activeThread : runningThreads) {
                unpark(activeThread);
            }
        }
    }

    /**
     * Get the maximum pool size.  This is the absolute upper limit to the size of the thread pool.
     *
     * @return the maximum pool size
     * @see Builder#getMaximumPoolSize() Builder.getMaximumPoolSize()
     */
    public int getMaximumPoolSize() {
        return maxSizeOf(threadStatus);
    }

    /**
     * Set the maximum pool size.  If the configured maximum pool size is less than the configured core size, the
     * core size will be reduced to match the maximum size when the thread pool is constructed.
     *
     * @param maxSize the maximum pool size (must be greater than or equal to 0, and less than 2^20)
     * @see Builder#setMaximumPoolSize(int) Builder.setMaximumPoolSize()
     */
    public void setMaximumPoolSize(final int maxPoolSize) {
        Assert.checkMinimumParameter("maxPoolSize", 0, maxPoolSize);
        Assert.checkMaximumParameter("maxPoolSize", TS_THREAD_CNT_MASK, maxPoolSize);
        long oldVal, newVal;
        do {
            oldVal = threadStatus;
            if (maxPoolSize < coreSizeOf(oldVal)) {
                // automatically bump down core size to match
                newVal = withCoreSize(withMaxSize(oldVal, maxPoolSize), maxPoolSize);
            } else {
                newVal = withMaxSize(oldVal, maxPoolSize);
            }
        } while (! compareAndSetThreadStatus(oldVal, newVal));
        if (maxSizeOf(newVal) < maxSizeOf(oldVal) || coreSizeOf(newVal) < coreSizeOf(oldVal)) {
            // poke all the threads to try to terminate any excess eagerly
            for (Thread activeThread : runningThreads) {
                unpark(activeThread);
            }
        }
    }

    /**
     * Determine whether core threads are allowed to time out.  A "core thread" is defined as any thread in the pool
     * when the pool size is below the pool's {@linkplain #getCorePoolSize() core pool size}.
     *
     * @return {@code true} if core threads are allowed to time out, {@code false} otherwise
     * @see Builder#allowsCoreThreadTimeOut() Builder.allowsCoreThreadTimeOut()
     */
    public boolean allowsCoreThreadTimeOut() {
        return isAllowCoreTimeout(threadStatus);
    }

    /**
     * Establish whether core threads are allowed to time out.  A "core thread" is defined as any thread in the pool
     * when the pool size is below the pool's {@linkplain #getCorePoolSize() core pool size}.
     *
     * @param value {@code true} if core threads are allowed to time out, {@code false} otherwise
     * @see Builder#allowCoreThreadTimeOut(boolean) Builder.allowCoreThreadTimeOut()
     */
    public void allowCoreThreadTimeOut(boolean value) {
        long oldVal, newVal;
        do {
            oldVal = threadStatus;
            newVal = withAllowCoreTimeout(oldVal, value);
            if (oldVal == newVal) return;
        } while (! compareAndSetThreadStatus(oldVal, newVal));
        if (value) {
            // poke all the threads to try to terminate any excess eagerly
            for (Thread activeThread : runningThreads) {
                unpark(activeThread);
            }
        }
    }

    /**
     * Get the thread keep-alive time.  This is the minimum length of time that idle threads should remain until they exit.
     * Unless core threads are allowed to time out, threads will only exit if the current thread count exceeds the core
     * limit.
     *
     * @param keepAliveUnits the unit in which the result should be expressed (must not be {@code null})
     * @return the amount of time (will be greater than zero)
     * @see Builder#getKeepAliveTime(TimeUnit) Builder.getKeepAliveTime()
     */
    public long getKeepAliveTime(TimeUnit keepAliveUnits) {
        Assert.checkNotNullParam("keepAliveUnits", keepAliveUnits);
        return keepAliveUnits.convert(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Set the thread keep-alive time.  This is the minimum length of time that idle threads should remain until they exit.
     * Unless core threads are allowed to time out, threads will only exit if the current thread count exceeds the core
     * limit.
     *
     * @param keepAliveTime the thread keep-alive time (must be > 0)
     * @param keepAliveUnits the unit in which the value is expressed (must not be {@code null})
     * @see Builder#setKeepAliveTime(long, TimeUnit) Builder.setKeepAliveTime()
     */
    public void setKeepAliveTime(final long keepAliveTime, final TimeUnit keepAliveUnits) {
        Assert.checkMinimumParameter("keepAliveTime", 1L, keepAliveTime);
        Assert.checkNotNullParam("keepAliveUnits", keepAliveUnits);
        timeoutNanos = max(1L, keepAliveUnits.toNanos(keepAliveTime));
    }

    /**
     * Get the maximum queue size.  If the queue is full and a task cannot be immediately accepted, rejection will result.
     *
     * @return the maximum queue size
     * @see Builder#getMaximumQueueSize() Builder.getMaximumQueueSize()
     */
    public int getMaximumQueueSize() {
        return maxQueueSizeOf(queueSize);
    }

    /**
     * Set the maximum queue size.  If the new maximum queue size is smaller than the current queue size, there is no
     * effect other than preventing tasks from being enqueued until the size decreases below the maximum again.
     *
     * @param maxQueueSize the maximum queue size (must be ≥ 0)
     * @see Builder#setMaximumQueueSize(int) Builder.setMaximumQueueSize()
     */
    public void setMaximumQueueSize(final int maxQueueSize) {
        Assert.checkMinimumParameter("maxQueueSize", 0, maxQueueSize);
        Assert.checkMaximumParameter("maxQueueSize", Integer.MAX_VALUE, maxQueueSize);
        long oldVal;
        do {
            oldVal = queueSize;
        } while (! compareAndSetQueueSize(oldVal, withMaxQueueSize(oldVal, maxQueueSize)));
    }

    /**
     * Get the executor to delegate to in the event of task rejection.
     *
     * @return the executor to delegate to in the event of task rejection (not {@code null})
     */
    public Executor getHandoffExecutor() {
        return handoffExecutor;
    }

    /**
     * Set the executor to delegate to in the event of task rejection.
     *
     * @param handoffExecutor the executor to delegate to in the event of task rejection (must not be {@code null})
     */
    public void setHandoffExecutor(final Executor handoffExecutor) {
        Assert.checkNotNullParam("handoffExecutor", handoffExecutor);
        this.handoffExecutor = handoffExecutor;
    }

    /**
     * Get the exception handler to use for uncaught exceptions.
     *
     * @return the exception handler to use for uncaught exceptions (not {@code null})
     */
    public Thread.UncaughtExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    /**
     * Set the exception handler to use for uncaught exceptions.
     *
     * @param exceptionHandler the exception handler to use for uncaught exceptions (must not be {@code null})
     */
    public void setExceptionHandler(final Thread.UncaughtExceptionHandler exceptionHandler) {
        Assert.checkNotNullParam("exceptionHandler", exceptionHandler);
        this.exceptionHandler = exceptionHandler;
    }

    // =======================================================
    // Statistics & metrics API
    // =======================================================

    /**
     * Get an estimate of the current queue size.
     *
     * @return an estimate of the current queue size
     */
    public int getQueueSize() {
        return currentQueueSizeOf(queueSize);
    }

    /**
     * Get an estimate of the peak number of threads that the pool has ever held.
     *
     * @return an estimate of the peak number of threads that the pool has ever held
     */
    public int getLargestPoolSize() {
        return peakThreadCount;
    }

    /**
     * Get an estimate of the number of threads which are currently doing work on behalf of the thread pool.
     *
     * @return the active count estimate
     */
    public int getActiveCount() {
        return activeCount;
    }

    /**
     * Get an estimate of the peak size of the queue.
     *
     * @return an estimate of the peak size of the queue
     */
    public int getLargestQueueSize() {
        return peakQueueSize;
    }

    /**
     * Get an estimate of the total number of tasks ever submitted to this thread pool.
     *
     * @return an estimate of the total number of tasks ever submitted to this thread pool
     */
    public long getSubmittedTaskCount() {
        return submittedTaskCounter.longValue();
    }

    /**
     * Get an estimate of the total number of tasks ever rejected by this thread pool for any reason.
     *
     * @return an estimate of the total number of tasks ever rejected by this thread pool
     */
    public long getRejectedTaskCount() {
        return rejectedTaskCounter.longValue();
    }

    /**
     * Get an estimate of the number of tasks completed by this thread pool.
     *
     * @return an estimate of the number of tasks completed by this thread pool
     */
    public long getCompletedTaskCount() {
        return completedTaskCounter.longValue();
    }

    /**
     * Get an estimate of the current number of active threads in the pool.
     *
     * @return an estimate of the current number of active threads in the pool
     */
    public int getPoolSize() {
        return currentSizeOf(threadStatus);
    }

    // =======================================================
    // Pooled thread body
    // =======================================================

    final class ThreadBody implements Runnable {
        private Runnable initialTask;

        ThreadBody(final Runnable initialTask) {
            this.initialTask = initialTask;
        }

        /**
         * Execute the body of the thread.  On entry the thread is added to the {@link #runningThreads} set, and on
         * exit it is removed.
         */
        public void run() {
            final Thread currentThread = Thread.currentThread();
            runningThreads.add(currentThread);
            TaskNode head;
            QNode headNext;
            try {
                final Runnable initialTask = this.initialTask;
                if (initialTask != null) {
                    if (UPDATE_STATISTICS) incrementActiveCount();
                    safeRun(initialTask);
                    if (UPDATE_STATISTICS) {
                        decrementActiveCount();
                        completedTaskCounter.increment();
                    }
                }
            } finally {
                this.initialTask = null;
            }
            processingQueue: for (;;) {
                head = EnhancedQueueExecutor.this.head;
                headNext = head.getNext();
                if (headNext instanceof TaskNode) {
                    TaskNode taskNode = (TaskNode) headNext;
                    if (compareAndSetHead(head, taskNode)) {
                        if (! NO_QUEUE_LIMIT) decreaseQueueSize();
                        if (isShutdownInterrupt(threadStatus)) {
                            currentThread.interrupt();
                        } else {
                            Thread.interrupted();
                        }
                        Runnable task = taskNode.task;
                        if (task != null) {
                            if (UPDATE_STATISTICS) incrementActiveCount();
                            safeRun(task);
                            if (UPDATE_STATISTICS) {
                                decrementActiveCount();
                                completedTaskCounter.increment();
                            }
                        }
                    }
                    // next!
                    continue;
                } else if (headNext instanceof PoolThreadNode || headNext == null) {
                    PoolThreadNode newNode;
                    newNode = new PoolThreadNode((PoolThreadNode) headNext, currentThread);
                    if (head.compareAndSetNext(headNext, newNode)) {
                        // at this point, we are registered into the queue
                        long start = System.nanoTime();
                        long elapsed = 0L;
                        waitingForTask: for (;;) {
                            Runnable task = newNode.getTask();
                            assert task != ACCEPTED && task != GAVE_UP;
                            if (task != WAITING && task != EXIT) {
                                if (newNode.compareAndSetTask(task, ACCEPTED)) {
                                    // we have a task to run, so run it and then abandon the node
                                    if (isShutdownInterrupt(threadStatus)) {
                                        currentThread.interrupt();
                                    } else {
                                        Thread.interrupted();
                                    }
                                    if (task != null) {
                                        if (UPDATE_STATISTICS) incrementActiveCount();
                                        safeRun(task);
                                        if (UPDATE_STATISTICS) {
                                            decrementActiveCount();
                                            completedTaskCounter.increment();
                                        }
                                    }
                                    // rerun outer
                                    continue processingQueue;
                                }
                                // we had a task to run, but we failed to CAS it for some reason, so retry
                                continue waitingForTask;
                            } else {
                                final long timeoutNanos = EnhancedQueueExecutor.this.timeoutNanos;
                                long oldVal = threadStatus;
                                if (elapsed >= timeoutNanos || task == EXIT || currentSizeOf(oldVal) > maxSizeOf(oldVal)) {
                                    if (newNode.compareAndSetTask(task, GAVE_UP)) {
                                        // try to exit this thread
                                        long newVal;
                                        for (;;) {
                                            if (task == EXIT ||
                                                isShutdownRequested(oldVal) ||
                                                isAllowCoreTimeout(oldVal) ||
                                                currentSizeOf(oldVal) >= coreSizeOf(oldVal)
                                            ) {
                                                newVal = withCurrentSize(oldVal, currentSizeOf(oldVal) - 1);
                                                if (currentSizeOf(newVal) == 0 && isShutdownRequested(newVal)) {
                                                    newVal = withShutdownComplete(newVal);
                                                }
                                            } else {
                                                // nope, we're a core thread & can't exit right now.  Just keep parking.
                                                newNode.compareAndSetTask(GAVE_UP, WAITING);
                                                park(EnhancedQueueExecutor.this);
                                                Thread.interrupted();
                                                elapsed = System.nanoTime() - start;
                                                // retry wait-for-task loop
                                                continue waitingForTask;
                                            }
                                            if (compareAndSetThreadStatus(oldVal, newVal)) break;
                                            oldVal = threadStatus;
                                        }
                                        // clear to exit.
                                        runningThreads.remove(currentThread);
                                        if (isShutdownComplete(newVal)) {
                                            completeTermination();
                                        }
                                        return;
                                    }
                                    // otherwise retry inner
                                    continue waitingForTask;
                                } else {
                                    assert task == WAITING;
                                    parkNanos(EnhancedQueueExecutor.this, timeoutNanos - elapsed);
                                    Thread.interrupted();
                                    elapsed = System.nanoTime() - start;
                                    // retry inner
                                    continue waitingForTask;
                                }
                            }
                            //throw Assert.unreachableCode();
                        } // :waitingForTask
                        //throw Assert.unreachableCode();
                    } else {
                        continue processingQueue;
                    }
                    //throw Assert.unreachableCode();
                } else {
                    assert headNext instanceof TerminateWaiterNode;
                    // we're shutting down!
                    long oldVal, newVal;
                    do {
                        oldVal = threadStatus;
                        assert isShutdownRequested(oldVal);
                        newVal = withCurrentSize(oldVal, currentSizeOf(oldVal) - 1);
                        if (currentSizeOf(newVal) == 0) {
                            newVal = withShutdownComplete(newVal);
                        }
                    } while (! compareAndSetThreadStatus(oldVal, newVal));
                    runningThreads.remove(currentThread);
                    if (isShutdownComplete(newVal)) {
                        completeTermination();
                    }
                    return;
                }
            } // :processingQueue
            //throw Assert.unreachableCode();
        }
    }

    // =======================================================
    // Thread starting
    // =======================================================

    /**
     * Attempt to create a thread which will initially run the (optional) given task.  If this method returns {@code false},
     * it means the task was not accepted for processing and must be dealt with another way.  If this method returns {@code true},
     * then the task is consumed  (either by creating a thread, or because a rejection handler handled the task some other way).
     *
     * @param runnable the initial task (may be {@code null})
     * @param growthResistance the growth resistance to apply
     * @return {@code true} if the task was handled, {@code false} otherwise
     * @throws RejectedExecutionException if the thread could not be started, or shutdown was requested, and the rejection
     *      handler throws this exception
     */
    boolean tryCreateThreadForTask(final Runnable runnable, final float growthResistance) throws RejectedExecutionException {
        int oldSize, newSize;
        long oldStat;
        for (;;) {
            oldStat = threadStatus;
            if (isShutdownRequested(oldStat)) {
                if (UPDATE_STATISTICS && runnable != null) rejectedTaskCounter.increment();
                rejectShutdown(runnable);
                return true;
            }
            oldSize = currentSizeOf(oldStat);
            if (oldSize >= maxSizeOf(oldStat)) {
                // max threads already reached; insert a queue node
                return false;
            }
            if (oldSize >= coreSizeOf(oldStat) && oldSize > 0) {
                // core threads are reached; check resistance factor (if no threads are running, then always start a thread)
                if (growthResistance != 0.0f && (growthResistance == 1.0f || ThreadLocalRandom.current().nextFloat() < growthResistance)) {
                    // queue the task instead of starting a thread
                    return false;
                }
            }
            // try to increase
            newSize = oldSize + 1;
            // state change ex3:
            //   threadStatus.size ← threadStatus(snapshot).size + 1
            // cannot succeed: sh1
            // succeeds: -
            // preconditions:
            //   ! threadStatus(snapshot).shutdownRequested
            //   threadStatus(snapshot).size < threadStatus(snapshot).maxSize
            //   threadStatus(snapshot).size < threadStatus(snapshot).coreSize || random < growthResistance
            // post-actions (fail):
            //   retry whole loop
            if (compareAndSetThreadStatus(oldStat, withCurrentSize(oldStat, newSize))) {
                boolean ok = false;
                try {
                    // got permission to (try to) start a thread
                    Thread thread;
                    try {
                        thread = threadFactory.newThread(new ThreadBody(runnable));
                    } catch (Throwable t) {
                        if (UPDATE_STATISTICS && runnable != null) rejectedTaskCounter.increment();
                        rejectException(runnable, t);
                        return true;
                    }
                    if (thread == null) {
                        if (UPDATE_STATISTICS && runnable != null) rejectedTaskCounter.increment();
                        rejectNoThread(runnable);
                        return true;
                    }
                    try {
                        thread.start();
                    } catch (Throwable t) {
                        if (UPDATE_STATISTICS && runnable != null) rejectedTaskCounter.increment();
                        rejectException(runnable, t);
                        return true;
                    }
                    ok = true;
                    // increment peak thread count
                    if (UPDATE_STATISTICS) {
                        submittedTaskCounter.increment();
                        int oldVal;
                        do {
                            oldVal = peakThreadCount;
                            if (oldVal >= newSize) break;
                        } while (! compareAndSetPeakThreadCount(oldVal, newSize));
                    }
                    return true;
                } finally {
                    if (! ok) {
                        // roll back our thread allocation attempt
                        // state change ex4:
                        //   threadStatus.size ← threadStatus.size - 1
                        // succeeds: ex3
                        // preconditions:
                        //   threadStatus.size > 0
                        do {
                            oldStat = threadStatus;
                            // newSize and oldSize are no longer valid here
                        }
                        while (! compareAndSetThreadStatus(oldStat, withCurrentSize(oldStat, currentSizeOf(oldStat) - 1)));
                    }
                }
            }
            // retry loop
        }
    }

    // =======================================================
    // Termination task
    // =======================================================

    void completeTermination() {
        // be kind and un-interrupt the thread for the termination task
        Thread.interrupted();
        safeRun(terminationTask);
        // notify all waiters
        QNode tail = EnhancedQueueExecutor.this.tail;
        QNode tailNext = tail.getAndSetNext(TERMINATE_COMPLETE);
        while (tailNext != null) {
            // state change ct1:
            //    tail(snapshot).next(snapshot).thread ← null
            // succeeds: sh2
            // preconditions:
            //    threadStatus is shutdown (because sh1 ≺ … ≺ ct1)
            // postconditions: -
            // post-actions:
            //    unpark(twn)
            if (tailNext instanceof TerminateWaiterNode) {
                unpark(((TerminateWaiterNode) tailNext).getAndClearThread());
            }
            tailNext = tailNext.getNext();
        }
    }

    // =======================================================
    // Compare-and-set operations
    // =======================================================

    void incrementActiveCount() {
        activeCountUpdater.incrementAndGet(this);
    }

    void decrementActiveCount() {
        activeCountUpdater.decrementAndGet(this);
    }

    boolean compareAndSetThreadStatus(final long expect, final long update) {
        return threadStatusUpdater.compareAndSet(this, expect, update);
    }

    boolean compareAndSetHead(final TaskNode expect, final TaskNode update) {
        return headUpdater.compareAndSet(this, expect, update);
    }

    boolean compareAndSetPeakThreadCount(final int expect, final int update) {
        return peakThreadCountUpdater.compareAndSet(this, expect, update);
    }

    boolean compareAndSetPeakQueueSize(final int expect, final int update) {
        return peakQueueSizeUpdater.compareAndSet(this, expect, update);
    }

    boolean compareAndSetQueueSize(final long expect, final long update) {
        return queueSizeUpdater.compareAndSet(this, expect, update);
    }

    void compareAndSetTail(final TaskNode expect, final TaskNode update) {
        tailUpdater.compareAndSet(this, expect, update);
    }

    // =======================================================
    // Queue size operations
    // =======================================================

    boolean increaseQueueSize() {
        long oldVal;
        int oldSize;
        int newSize;
        do {
            oldVal = queueSize;
            oldSize = currentQueueSizeOf(oldVal);
            if (oldSize >= maxQueueSizeOf(oldVal)) {
                // reject
                return false;
            }
            newSize = oldSize + 1;
        } while (! compareAndSetQueueSize(oldVal, withCurrentQueueSize(oldVal, newSize)));
        if (UPDATE_STATISTICS) {
            do {
                // oldSize now represents the old peak size
                oldSize = peakQueueSize;
                if (newSize <= oldSize) break;
            } while (! compareAndSetPeakQueueSize(oldSize, newSize));
        }
        return true;
    }

    void decreaseQueueSize() {
        long oldVal;
        do {
            oldVal = queueSize;
            assert currentQueueSizeOf(oldVal) > 0;
        } while (! compareAndSetQueueSize(oldVal, withCurrentQueueSize(oldVal, currentQueueSizeOf(oldVal) - 1)));
    }

    // =======================================================
    // Inline Functions
    // =======================================================

    static int currentQueueSizeOf(long queueSize) {
        return (int) (queueSize & 0x7fff_ffff);
    }

    static long withCurrentQueueSize(long queueSize, int current) {
        assert current >= 0;
        return queueSize & 0xffff_ffff_0000_0000L | current;
    }

    static int maxQueueSizeOf(long queueSize) {
        return (int) (queueSize >>> 32 & 0x7fff_ffff);
    }

    static long withMaxQueueSize(long queueSize, int max) {
        assert max >= 0;
        return queueSize & 0xffff_ffffL | (long)max << 32;
    }

    static int coreSizeOf(long status) {
        return (int) (status >>> TS_CORE_SHIFT & TS_THREAD_CNT_MASK);
    }

    static int maxSizeOf(long status) {
        return (int) (status >>> TS_MAX_SHIFT & TS_THREAD_CNT_MASK);
    }

    static int currentSizeOf(long status) {
        return (int) (status >>> TS_CURRENT_SHIFT & TS_THREAD_CNT_MASK);
    }

    static long withCoreSize(long status, int newCoreSize) {
        assert 0 <= newCoreSize && newCoreSize <= TS_THREAD_CNT_MASK;
        return status & ~(TS_THREAD_CNT_MASK << TS_CORE_SHIFT) | (long)newCoreSize << TS_CORE_SHIFT;
    }

    static long withCurrentSize(long status, int newCurrentSize) {
        assert 0 <= newCurrentSize && newCurrentSize <= TS_THREAD_CNT_MASK;
        return status & ~(TS_THREAD_CNT_MASK << TS_CURRENT_SHIFT) | (long)newCurrentSize << TS_CURRENT_SHIFT;
    }

    static long withMaxSize(long status, int newMaxSize) {
        assert 0 <= newMaxSize && newMaxSize <= TS_THREAD_CNT_MASK;
        return status & ~(TS_THREAD_CNT_MASK << TS_MAX_SHIFT) | (long)newMaxSize << TS_MAX_SHIFT;
    }

    static long withShutdownRequested(final long status) {
        return status | TS_SHUTDOWN_REQUESTED;
    }

    static long withShutdownComplete(final long status) {
        return status | TS_SHUTDOWN_COMPLETE;
    }

    static long withShutdownInterrupt(final long status) {
        return status | TS_SHUTDOWN_INTERRUPT;
    }

    static long withAllowCoreTimeout(final long status, final boolean allowed) {
        return allowed ? status | TS_ALLOW_CORE_TIMEOUT : status & ~TS_ALLOW_CORE_TIMEOUT;
    }

    static boolean isShutdownRequested(final long status) {
        return (status & TS_SHUTDOWN_REQUESTED) != 0;
    }

    static boolean isShutdownComplete(final long status) {
        return (status & TS_SHUTDOWN_COMPLETE) != 0;
    }

    static boolean isShutdownInterrupt(final long threadStatus) {
        return (threadStatus & TS_SHUTDOWN_INTERRUPT) != 0;
    }

    static boolean isAllowCoreTimeout(final long oldVal) {
        return (oldVal & TS_ALLOW_CORE_TIMEOUT) != 0;
    }

    // =======================================================
    // Static configuration
    // =======================================================

    private static boolean readBooleanProperty(String name, boolean defVal) {
        return Boolean.parseBoolean(readProperty(name, Boolean.toString(defVal)));
    }

    private static String readProperty(String name, String defVal) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            return AccessController.doPrivileged(new PrivilegedAction<String>() {
                public String run() {
                    return readPropertyRaw(name, defVal);
                }
            });
        } else {
            return readPropertyRaw(name, defVal);
        }
    }

    private static String readPropertyRaw(final String name, final String defVal) {
        return System.getProperty("jboss.threads.eqe." + name, defVal);
    }

    // =======================================================
    // Utilities
    // =======================================================

    void safeRun(final Runnable task) {
        try {
            if (task != null) task.run();
        } catch (Throwable t) {
            try {
                exceptionHandler.uncaughtException(Thread.currentThread(), t);
            } catch (Throwable ignored) {
                // nothing else we can safely do here
            }
        } finally {
            // clear interrupt status
            Thread.interrupted();
        }
    }

    void rejectException(final Runnable task, final Throwable cause) {
        try {
            handoffExecutor.execute(task);
        } catch (Throwable t) {
            t.addSuppressed(cause);
            throw t;
        }
    }

    void rejectNoThread(final Runnable task) {
        try {
            handoffExecutor.execute(task);
        } catch (Throwable t) {
            t.addSuppressed(new RejectedExecutionException("No threads available"));
            throw t;
        }
    }

    void rejectQueueFull(final Runnable task) {
        try {
            handoffExecutor.execute(task);
        } catch (Throwable t) {
            t.addSuppressed(new RejectedExecutionException("Queue is full"));
            throw t;
        }
    }

    void rejectShutdown(final Runnable task) {
        try {
            handoffExecutor.execute(task);
        } catch (Throwable t) {
            t.addSuppressed(new RejectedExecutionException("Executor is being shut down"));
            throw t;
        }
    }

    // =======================================================
    // Node classes
    // =======================================================

    abstract static class QNode {
        // in 9, use VarHandle
        private static final AtomicReferenceFieldUpdater<QNode, QNode> nextUpdater = AtomicReferenceFieldUpdater.newUpdater(QNode.class, QNode.class, "next");

        @SuppressWarnings("unused")
        private volatile QNode next;

        QNode(final QNode next) {
            this.next = next;
        }

        boolean compareAndSetNext(QNode expect, QNode update) {
            return nextUpdater.compareAndSet(this, expect, update);
        }

        QNode getNext() {
            return nextUpdater.get(this);
        }

        QNode getAndSetNext(final QNode node) {
            return nextUpdater.getAndSet(this, node);
        }
    }

    static final class PoolThreadNode extends QNode {
        private final Thread thread;

        @SuppressWarnings("unused")
        private volatile Runnable task;

        private static final AtomicReferenceFieldUpdater<PoolThreadNode, Runnable> taskUpdater = AtomicReferenceFieldUpdater.newUpdater(PoolThreadNode.class, Runnable.class, "task");

        PoolThreadNode(final PoolThreadNode next, final Thread thread) {
            super(next);
            this.thread = thread;
            task = WAITING;
        }

        Thread getThread() {
            return thread;
        }

        boolean compareAndSetTask(final Runnable expect, final Runnable update) {
            return taskUpdater.compareAndSet(this, expect, update);
        }

        Runnable getTask() {
            return taskUpdater.get(this);
        }

        PoolThreadNode getNext() {
            return (PoolThreadNode) super.getNext();
        }
    }

    static final class TerminateWaiterNode extends QNode {
        private volatile Thread thread;

        TerminateWaiterNode(final Thread thread) {
            // always start with a {@code null} next
            super(null);
            this.thread = thread;
        }

        Thread getAndClearThread() {
            // doesn't have to be particularly atomic
            try {
                return thread;
            } finally {
                thread = null;
            }
        }

        TerminateWaiterNode getNext() {
            return (TerminateWaiterNode) super.getNext();
        }
    }

    static final class TaskNode extends QNode {
        volatile Runnable task;
        final LongAdder completedTaskCounter;

        TaskNode(final Runnable task, final LongAdder completedTaskCounter) {
            // we always start task nodes with a {@code null} next
            super(null);
            this.completedTaskCounter = completedTaskCounter;
            this.task = task;
        }
    }
}
