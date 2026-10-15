package org.jboss.threads;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.jboss.threads.Messages.msg;

import io.smallrye.common.constraint.Assert;

/**
 * A queue-less view executor that delegates all tasks immediately to an underlying {@link Executor},
 * tracking submitted and running task counts via a single atomic state word. Limits on the number
 * of submitted-but-not-started tasks (queue limit) and total outstanding tasks (capacity limit)
 * can be enforced and reconfigured at runtime.
 *
 * <p>Unlike {@link EnhancedViewExecutor}, this implementation maintains no physical task queue.
 * Every accepted task is immediately submitted to the delegate executor. Consequently, it cannot
 * enforce a parallelism limit (which would require delaying execution). Instead, it provides
 * counter-based admission control: tasks are rejected when limits are exceeded.
 *
 * <p>Support for {@link #shutdownNow()} returning unstarted tasks and for interrupting running
 * tasks on shutdown are independently optional, controlled at construction time. When disabled,
 * no per-task tracking overhead is incurred.
 */
final class SimpleViewExecutor extends ViewExecutor {

    // ==============================================================================================
    // State word layout
    //
    //   Bit 63:     shutdown requested
    //   Bit 62:     interrupt requested
    //   Bit 31..61: submitted-but-not-started count (31 bits)
    //   Bit 00..30: running count (31 bits)
    // ==============================================================================================

    private static final long SHUTDOWN_MASK = 1L << 63;
    private static final long INTERRUPT_MASK = 1L << 62;
    private static final int SUBMITTED_SHIFT = 31;
    private static final long SUBMITTED_ONE = 1L << SUBMITTED_SHIFT;
    private static final long SUBMITTED_MASK = ((1L << 31) - 1) << SUBMITTED_SHIFT;
    private static final long RUNNING_MASK = (1L << 31) - 1;
    private static final long RUNNING_ONE = 1L;

    // ==============================================================================================
    // Limits word layout
    //
    //   Bit 32..63: capacity limit (32 bits, 0 = no limit)
    //   Bit 00..31: queue limit (32 bits, 0 = no limit)
    // ==============================================================================================

    private static final int CAPACITY_LIMIT_SHIFT = 32;
    private static final long QUEUE_LIMIT_MASK = 0xFFFF_FFFFL;

    // ==============================================================================================
    // VarHandles
    // ==============================================================================================

    private static final VarHandle stateHandle = ConstantBootstraps.fieldVarHandle(
            MethodHandles.lookup(), "state", VarHandle.class, SimpleViewExecutor.class, long.class);
    private static final VarHandle limitsHandle = ConstantBootstraps.fieldVarHandle(
            MethodHandles.lookup(), "limits", VarHandle.class, SimpleViewExecutor.class, long.class);

    // ==============================================================================================
    // Fields
    // ==============================================================================================

    private final Executor delegate;

    /**
     * Atomic state word containing shutdown/interrupt flags and submitted/running counts.
     */
    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long state;

    /**
     * Atomic limits word containing the capacity limit (upper 32 bits) and queue limit (lower 32 bits).
     * Both values are unsigned; 0 means no limit.
     */
    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long limits;

    /**
     * Optional set of submitted-but-not-started tasks. When non-null, enables {@link #shutdownNow()}
     * to return unstarted tasks. When null, {@code shutdownNow()} returns an empty list.
     */
    private final Set<TaskWrapper> submittedTasks;

    /**
     * Optional set of currently-running tasks. When non-null, enables interrupting running tasks
     * on {@link #shutdown(boolean) shutdown(true)} or {@link #shutdownNow()}.
     */
    private final Set<TaskWrapper> runningTasks;

    /**
     * Threads currently blocked in {@link #awaitTermination(long, TimeUnit)}.
     */
    private final CopyOnWriteArraySet<Thread> terminationWaiters = new CopyOnWriteArraySet<>();

    // ==============================================================================================
    // Construction
    // ==============================================================================================

    /**
     * Construct a new instance.
     *
     * @param delegate the executor to delegate task execution to (must not be {@code null})
     * @param queueLimit the maximum number of submitted-but-not-started tasks (0 for no limit)
     * @param capacityLimit the maximum total outstanding tasks (0 for no limit)
     * @param trackSubmitted {@code true} to enable {@link #shutdownNow()} returning unstarted tasks
     * @param trackRunning {@code true} to enable interrupting running tasks on shutdown
     * @param handler the uncaught exception handler (must not be {@code null})
     */
    SimpleViewExecutor(
            final Executor delegate,
            final int queueLimit,
            final int capacityLimit,
            final boolean trackSubmitted,
            final boolean trackRunning,
            final Thread.UncaughtExceptionHandler handler) {
        this.delegate = Assert.checkNotNullParam("delegate", delegate);
        Assert.checkMinimumParameter("queueLimit", 0, queueLimit);
        Assert.checkMinimumParameter("capacityLimit", 0, capacityLimit);
        if (capacityLimit > 0 && capacityLimit < queueLimit) {
            throw msg.capacityLimitMustBeGreaterThanQueueLimit();
        }
        this.limits = packLimits(queueLimit, capacityLimit);
        this.submittedTasks = trackSubmitted ? ConcurrentHashMap.newKeySet() : null;
        this.runningTasks = trackRunning ? ConcurrentHashMap.newKeySet() : null;
        setExceptionHandler(Assert.checkNotNullParam("handler", handler));
    }

    // ==============================================================================================
    // State word helpers
    // ==============================================================================================

    private static boolean isShutdown(long state) {
        return (state & SHUTDOWN_MASK) != 0;
    }

    private static boolean isInterrupt(long state) {
        return (state & INTERRUPT_MASK) != 0;
    }

    private static int getSubmitted(long state) {
        return (int) ((state & SUBMITTED_MASK) >>> SUBMITTED_SHIFT);
    }

    private static int getRunning(long state) {
        return (int) (state & RUNNING_MASK);
    }

    /**
     * The executor is terminated when shutdown is set and both counts are zero.
     *
     * @param state the state word snapshot
     * @return {@code true} if the state represents a terminated executor
     */
    private static boolean isTerminated(long state) {
        return state == SHUTDOWN_MASK || state == (SHUTDOWN_MASK | INTERRUPT_MASK);
    }

    /**
     * Perform a {@code compareAndExchange} on the state word.
     *
     * @param expected the expected value
     * @param update the desired new value
     * @return the witness value (equals {@code expected} on success)
     */
    private long casState(long expected, long update) {
        return (long) stateHandle.compareAndExchange(this, expected, update);
    }

    // ==============================================================================================
    // Limits word helpers
    // ==============================================================================================

    private static long packLimits(int queueLimit, int capacityLimit) {
        return ((long) capacityLimit << CAPACITY_LIMIT_SHIFT) | (queueLimit & QUEUE_LIMIT_MASK);
    }

    private static int getQueueLimit(long limits) {
        return (int) (limits & QUEUE_LIMIT_MASK);
    }

    private static int getCapacityLimit(long limits) {
        return (int) (limits >>> CAPACITY_LIMIT_SHIFT);
    }

    // ==============================================================================================
    // Termination helpers
    // ==============================================================================================

    /**
     * If the given state represents termination, unpark all waiting threads and run the termination task.
     *
     * @param state the state word snapshot to check
     */
    private void notifyTermination(long state) {
        if (isTerminated(state)) {
            for (Thread t : terminationWaiters) {
                LockSupport.unpark(t);
            }
            runTermination();
        }
    }

    // ==============================================================================================
    // Public API
    // ==============================================================================================

    @Override
    public void execute(final Runnable task) {
        Assert.checkNotNullParam("task", task);
        final TaskWrapper wrapper = new TaskWrapper(
                task, JBossExecutors.getContextClassLoader(Thread.currentThread()));
        final long limits = (long) limitsHandle.getVolatile(this);
        final int queueLimit = getQueueLimit(limits);
        final int capacityLimit = getCapacityLimit(limits);
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot)) {
                throw msg.shutDownInitiated();
            }
            final int submitted = getSubmitted(snapshot);
            final int running = getRunning(snapshot);
            if (queueLimit > 0 && submitted >= queueLimit) {
                throw msg.queueLimitReached();
            }
            if (capacityLimit > 0 && submitted + running >= capacityLimit) {
                throw msg.capacityLimitReached();
            }
            // Increment submitted count
            long newState = snapshot + SUBMITTED_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                break;
            }
            snapshot = witness;
        }
        // Submitted count is incremented; now submit to the delegate.
        final Set<TaskWrapper> submittedTasks = this.submittedTasks;
        if (submittedTasks != null) {
            submittedTasks.add(wrapper);
        }
        try {
            delegate.execute(wrapper);
        } catch (Throwable t) {
            // Delegate rejected — undo everything
            if (submittedTasks != null) {
                submittedTasks.remove(wrapper);
            }
            // Decrement submitted count
            snapshot = (long) stateHandle.getVolatile(this);
            for (;;) {
                long newState = snapshot - SUBMITTED_ONE;
                long witness = casState(snapshot, newState);
                if (witness == snapshot) {
                    notifyTermination(newState);
                    break;
                }
                snapshot = witness;
            }
            // If the delegate is a shut-down ExecutorService, shut down this view too
            if (delegate instanceof ExecutorService es && es.isShutdown()) {
                shutdown(false);
            }
            throw t;
        }
    }

    @Override
    public void shutdown(final boolean interrupt) {
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot) && (!interrupt || isInterrupt(snapshot))) {
                break;
            }
            long newState = snapshot | SHUTDOWN_MASK;
            if (interrupt) {
                newState |= INTERRUPT_MASK;
            }
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                if (interrupt) {
                    interruptRunningTasks();
                }
                notifyTermination(newState);
                break;
            }
            snapshot = witness;
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        // Set shutdown + interrupt, and zero the submitted count atomically
        long snapshot = (long) stateHandle.getVolatile(this);
        int submittedCount;
        for (;;) {
            submittedCount = getSubmitted(snapshot);
            long newState = (snapshot & ~(SUBMITTED_MASK)) | SHUTDOWN_MASK | INTERRUPT_MASK;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                interruptRunningTasks();
                notifyTermination(newState);
                break;
            }
            snapshot = witness;
        }
        // Collect submitted-but-not-started tasks if tracking is enabled
        final Set<TaskWrapper> submittedTasks = this.submittedTasks;
        if (submittedTasks != null && submittedCount > 0) {
            final ArrayList<Runnable> result = new ArrayList<>();
            for (TaskWrapper wrapper : submittedTasks) {
                if (wrapper.cancel()) {
                    result.add(wrapper.delegate);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return isShutdown((long) stateHandle.getVolatile(this));
    }

    @Override
    public boolean isTerminated() {
        return isTerminated((long) stateHandle.getVolatile(this));
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        if (isTerminated()) {
            return true;
        }
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        final Thread current = Thread.currentThread();
        terminationWaiters.add(current);
        try {
            while (!isTerminated()) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                LockSupport.parkNanos(this, remaining);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
            return true;
        } finally {
            terminationWaiters.remove(current);
        }
    }

    // ==============================================================================================
    // Limit reconfiguration
    // ==============================================================================================

    /**
     * Atomically reconfigure both limits.
     *
     * @param queueLimit the new queue limit (0 for no limit)
     * @param capacityLimit the new capacity limit (0 for no limit)
     * @throws IllegalArgumentException if both limits are non-zero and {@code capacityLimit < queueLimit}
     */
    public void setLimits(final int queueLimit, final int capacityLimit) {
        Assert.checkMinimumParameter("queueLimit", 0, queueLimit);
        Assert.checkMinimumParameter("capacityLimit", 0, capacityLimit);
        if (capacityLimit > 0 && capacityLimit < queueLimit) {
            throw msg.capacityLimitMustBeGreaterThanQueueLimit();
        }
        limitsHandle.setVolatile(this, packLimits(queueLimit, capacityLimit));
    }

    /**
     * Set the queue limit. If the new queue limit would exceed the current capacity limit
     * (and the capacity limit is non-zero), the capacity limit is automatically raised to match.
     *
     * @param queueLimit the new queue limit (0 for no limit)
     */
    public void setQueueLimit(final int queueLimit) {
        Assert.checkMinimumParameter("queueLimit", 0, queueLimit);
        long snapshot = (long) limitsHandle.getVolatile(this);
        for (;;) {
            int capacityLimit = getCapacityLimit(snapshot);
            if (capacityLimit > 0 && capacityLimit < queueLimit) {
                capacityLimit = queueLimit;
            }
            long newLimits = packLimits(queueLimit, capacityLimit);
            long witness = (long) limitsHandle.compareAndExchange(this, snapshot, newLimits);
            if (witness == snapshot) {
                return;
            }
            snapshot = witness;
        }
    }

    /**
     * Set the capacity limit. If the new capacity limit is non-zero and less than the current
     * queue limit, the queue limit is automatically reduced to match.
     *
     * @param capacityLimit the new capacity limit (0 for no limit)
     */
    public void setCapacityLimit(final int capacityLimit) {
        Assert.checkMinimumParameter("capacityLimit", 0, capacityLimit);
        long snapshot = (long) limitsHandle.getVolatile(this);
        for (;;) {
            int queueLimit = getQueueLimit(snapshot);
            if (capacityLimit > 0 && queueLimit > capacityLimit) {
                queueLimit = capacityLimit;
            }
            long newLimits = packLimits(queueLimit, capacityLimit);
            long witness = (long) limitsHandle.compareAndExchange(this, snapshot, newLimits);
            if (witness == snapshot) {
                return;
            }
            snapshot = witness;
        }
    }

    /**
     * Get the current queue limit.
     *
     * @return the queue limit (0 means no limit)
     */
    public int getQueueLimit() {
        return getQueueLimit((long) limitsHandle.getVolatile(this));
    }

    /**
     * Get the current capacity limit.
     *
     * @return the capacity limit (0 means no limit)
     */
    public int getCapacityLimit() {
        return getCapacityLimit((long) limitsHandle.getVolatile(this));
    }

    /**
     * Get the current number of submitted-but-not-started tasks.
     *
     * @return the submitted task count
     */
    public int getSubmittedCount() {
        return getSubmitted((long) stateHandle.getVolatile(this));
    }

    /**
     * Get the current number of running tasks.
     *
     * @return the running task count
     */
    public int getRunningCount() {
        return getRunning((long) stateHandle.getVolatile(this));
    }

    // ==============================================================================================
    // Internal helpers
    // ==============================================================================================

    /**
     * Interrupt all currently-running tasks, if running-task tracking is enabled.
     */
    private void interruptRunningTasks() {
        final Set<TaskWrapper> runningTasks = this.runningTasks;
        if (runningTasks != null) {
            for (TaskWrapper wrapper : runningTasks) {
                wrapper.interrupt();
            }
        }
    }

    /**
     * Retrieve the uncaught exception handler, falling back to the current thread's handler
     * or the default logging handler.
     *
     * @return the exception handler to use (never {@code null})
     */
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler handler = getExceptionHandler();
        if (handler != null) {
            return handler;
        }
        Thread.UncaughtExceptionHandler threadHandler =
                Thread.currentThread().getUncaughtExceptionHandler();
        return threadHandler != null ? threadHandler : JBossExecutors.loggingExceptionHandler();
    }

    @Override
    public String toString() {
        long snapshot = (long) stateHandle.getVolatile(this);
        long limitsSnapshot = (long) limitsHandle.getVolatile(this);
        return "SimpleViewExecutor{delegate=" + delegate
                + ", submitted=" + getSubmitted(snapshot)
                + ", running=" + getRunning(snapshot)
                + ", shutdown=" + isShutdown(snapshot)
                + ", terminated=" + isTerminated(snapshot)
                + ", queueLimit=" + getQueueLimit(limitsSnapshot)
                + ", capacityLimit=" + getCapacityLimit(limitsSnapshot)
                + '}';
    }

    // ==============================================================================================
    // TaskWrapper
    // ==============================================================================================

    /**
     * Wraps a submitted task with context class loader propagation, counter management,
     * and optional tracking set participation.
     */
    private final class TaskWrapper implements Runnable {

        /**
         * The delegate task to execute.
         * Safely published via the delegate executor's submission mechanism.
         * Set to {@code null} after cancellation or when execution begins (to allow GC).
         */
        Runnable delegate;

        /**
         * The context class loader captured at submission time.
         */
        private ClassLoader contextClassLoader;

        /**
         * The thread currently executing this task, or {@code null} if not running.
         * Guarded by {@code synchronized(this)} to prevent racing between interrupt
         * delivery and task completion (see {@link #interrupt()}).
         */
        private Thread thread;

        /**
         * Set to {@code true} when this task has been cancelled by {@link #shutdownNow()}.
         */
        private volatile boolean cancelled;

        TaskWrapper(final Runnable delegate, final ClassLoader contextClassLoader) {
            this.delegate = delegate;
            this.contextClassLoader = contextClassLoader;
        }

        @Override
        public void run() {
            // Remove from submitted set (if tracked)
            final Set<TaskWrapper> submittedTasks = SimpleViewExecutor.this.submittedTasks;
            if (submittedTasks != null) {
                submittedTasks.remove(this);
            }

            // Transition: submitted -= 1, running += 1
            // Net change on total: zero. Single CAX on the state word.
            long snapshot = (long) stateHandle.getVolatile(SimpleViewExecutor.this);
            for (;;) {
                long newState = snapshot - SUBMITTED_ONE + RUNNING_ONE;
                long witness = casState(snapshot, newState);
                if (witness == snapshot) {
                    snapshot = newState;
                    break;
                }
                snapshot = witness;
            }

            // Check for cancellation (set by shutdownNow after zeroing submitted count)
            if (cancelled) {
                // Undo the running increment — we're not actually running
                decrementRunning();
                return;
            }

            // Check shutdown + interrupt: skip payload if interrupted
            if (isShutdown(snapshot) && isInterrupt(snapshot)) {
                decrementRunning();
                return;
            }

            // Add to running set (if tracked)
            final Set<TaskWrapper> runningTasks = SimpleViewExecutor.this.runningTasks;
            final Thread currentThread = Thread.currentThread();
            this.thread = currentThread;
            if (runningTasks != null) {
                runningTasks.add(this);
                // Re-check interrupt flag — shutdown(true) may have been called between
                // our snapshot and adding ourselves to the running set
                if (isInterrupt((long) stateHandle.getVolatile(SimpleViewExecutor.this))) {
                    currentThread.interrupt();
                }
            }
            try {
                final Runnable delegate = this.delegate;
                final ClassLoader loader = this.contextClassLoader;
                // Allow GC of potentially large objects
                this.delegate = null;
                this.contextClassLoader = null;
                // Save and restore context class loader
                final ClassLoader old = JBossExecutors.getAndSetContextClassLoader(currentThread, loader);
                try {
                    delegate.run();
                } finally {
                    JBossExecutors.setContextClassLoader(currentThread, old);
                }
            } catch (Throwable t) {
                uncaughtExceptionHandler().uncaughtException(currentThread, t);
            } finally {
                if (runningTasks != null) {
                    runningTasks.remove(this);
                }
                // Hold the lock while clearing the thread reference to prevent interrupt()
                // from reading a stale reference and interrupting a thread that has moved
                // on to another task.
                synchronized (this) {
                    this.thread = null;
                }
                decrementRunning();
            }
        }

        /**
         * Mark this task as cancelled. Returns {@code true} if the task was successfully
         * cancelled (i.e., it had not already started or been cancelled).
         *
         * @return {@code true} if the task was cancelled by this call
         */
        boolean cancel() {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            Runnable d = delegate;
            delegate = null;
            contextClassLoader = null;
            return d != null;
        }

        /**
         * Interrupt the thread running this task, if any.
         * Synchronized to prevent racing with task completion clearing the thread reference.
         */
        void interrupt() {
            synchronized (this) {
                final Thread t = this.thread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }

        @Override
        public String toString() {
            return "SimpleViewExecutor.TaskWrapper{" + delegate + '}';
        }
    }

    /**
     * Decrement the running count in the state word and check for termination.
     */
    private void decrementRunning() {
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            long newState = snapshot - RUNNING_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                notifyTermination(newState);
                return;
            }
            snapshot = witness;
        }
    }
}
