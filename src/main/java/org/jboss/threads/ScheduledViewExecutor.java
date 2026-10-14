package org.jboss.threads;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.jboss.threads.Messages.msg;

import io.smallrye.common.constraint.Assert;

/**
 * A view executor that composes any {@link ViewExecutor} with a {@link ScheduledExecutorService},
 * adding scheduling capabilities. Immediate tasks ({@code execute}, {@code submit}) are delegated
 * to the composed {@code ViewExecutor}, while scheduled tasks are delegated to the composed
 * {@code ScheduledExecutorService} with shutdown coordination.
 *
 * <p>Scheduled tasks do not participate in the composed view executor's limit checking or counting.
 * They are tracked independently via a scheduled-futures set and a running-count in this class's
 * own state word. On shutdown, all unfired scheduled futures are cancelled.
 *
 * <p>A running scheduled task (one whose timer has fired and whose body is executing) participates
 * in this executor's running count, ensuring that {@link #awaitTermination(long, TimeUnit)} waits
 * for it to complete.
 */
public class ScheduledViewExecutor extends AbstractExecutorService implements ScheduledExecutorService {

    // ==============================================================================================
    // State word layout
    //
    //   Bit 63:     shutdown
    //   Bit 32..62: scheduling-in-flight count (31 bits)
    //   Bit 00..31: running-scheduled-tasks count (32 bits)
    // ==============================================================================================

    private static final long SHUTDOWN_MASK = 1L << 63;
    private static final int IN_FLIGHT_SHIFT = 32;
    private static final long IN_FLIGHT_ONE = 1L << IN_FLIGHT_SHIFT;
    private static final long IN_FLIGHT_MASK = ((1L << 31) - 1) << IN_FLIGHT_SHIFT;
    private static final long RUNNING_MASK = 0xFFFF_FFFFL;
    private static final long RUNNING_ONE = 1L;

    // ==============================================================================================
    // VarHandles
    // ==============================================================================================

    private static final VarHandle stateHandle = ConstantBootstraps.fieldVarHandle(
            MethodHandles.lookup(), "state", VarHandle.class, ScheduledViewExecutor.class, long.class);

    // ==============================================================================================
    // Fields
    // ==============================================================================================

    private final ViewExecutor view;
    private final ScheduledExecutorService ses;

    /**
     * Atomic state word containing this executor's own shutdown flag, in-flight scheduling count,
     * and running scheduled task count.
     */
    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long state;

    /**
     * Set of outstanding scheduled futures. Tasks add their future on scheduling and remove it
     * on completion or cancellation. Used by {@link #shutdown(boolean)} to cancel unfired tasks.
     */
    private final Set<ScheduledFuture<?>> scheduledFutures = ConcurrentHashMap.newKeySet();

    /**
     * Threads currently blocked in {@link #awaitTermination(long, TimeUnit)} waiting for
     * scheduled tasks to complete.
     */
    private final CopyOnWriteArraySet<Thread> scheduledWaiters = new CopyOnWriteArraySet<>();

    // ==============================================================================================
    // Construction
    // ==============================================================================================

    /**
     * Construct a new instance.
     *
     * @param view the view executor to delegate immediate tasks to (must not be {@code null})
     * @param ses the scheduled executor service to delegate scheduled tasks to (must not be {@code null})
     */
    public ScheduledViewExecutor(final ViewExecutor view, final ScheduledExecutorService ses) {
        this.view = Assert.checkNotNullParam("view", view);
        this.ses = Assert.checkNotNullParam("ses", ses);
    }

    // ==============================================================================================
    // State word helpers
    // ==============================================================================================

    private static boolean isShutdown(long state) {
        return (state & SHUTDOWN_MASK) != 0;
    }

    private static int getInFlight(long state) {
        return (int) ((state & IN_FLIGHT_MASK) >>> IN_FLIGHT_SHIFT);
    }

    private static int getRunning(long state) {
        return (int) (state & RUNNING_MASK);
    }

    /**
     * Terminated when shutdown is set and both in-flight and running counts are zero.
     *
     * @param state the state word snapshot
     * @return {@code true} if this executor's scheduled-task state is terminated
     */
    private static boolean isOwnTerminated(long state) {
        return state == SHUTDOWN_MASK;
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
    // Termination helpers
    // ==============================================================================================

    /**
     * If the given state represents termination of the scheduled-task side,
     * unpark all waiting threads.
     *
     * @param state the state word snapshot to check
     */
    private void notifyTermination(long state) {
        if (isOwnTerminated(state)) {
            for (Thread t : scheduledWaiters) {
                LockSupport.unpark(t);
            }
        }
    }

    // ==============================================================================================
    // Executor / ExecutorService delegation to view
    // ==============================================================================================

    @Override
    public void execute(final Runnable command) {
        if (isShutdown((long) stateHandle.getVolatile(this))) {
            throw msg.shutDownInitiated();
        }
        view.execute(command);
    }

    // ==============================================================================================
    // Shutdown
    // ==============================================================================================

    @Override
    public void shutdown() {
        shutdown(false);
    }

    /**
     * Initiate an orderly shutdown. New tasks and scheduled tasks are rejected after this call.
     * Previously submitted immediate tasks continue to execute. Previously scheduled tasks that
     * have not yet fired are cancelled. Running scheduled tasks are optionally interrupted.
     *
     * @param interrupt {@code true} to interrupt running scheduled tasks
     */
    public void shutdown(final boolean interrupt) {
        // Set our own shutdown bit first, so schedule() rejects immediately
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot)) {
                break;
            }
            long newState = snapshot | SHUTDOWN_MASK;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                notifyTermination(newState);
                break;
            }
            snapshot = witness;
        }
        // Shut down the composed view
        view.shutdown(interrupt);
        // Cancel all outstanding scheduled futures
        for (ScheduledFuture<?> f : scheduledFutures) {
            f.cancel(interrupt);
        }
        // Clear the set to release references. No new entries can be added (shutdown is set).
        // Stale entries from the wrapper-future-field race are cleaned up here.
        scheduledFutures.clear();
    }

    @Override
    public List<Runnable> shutdownNow() {
        // Set our own shutdown bit
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot)) {
                break;
            }
            long newState = snapshot | SHUTDOWN_MASK;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                notifyTermination(newState);
                break;
            }
            snapshot = witness;
        }
        // Shut down the composed view
        List<Runnable> result = view.shutdownNow();
        // Cancel all outstanding scheduled futures with interrupt
        for (ScheduledFuture<?> f : scheduledFutures) {
            f.cancel(true);
        }
        scheduledFutures.clear();
        return result;
    }

    @Override
    public boolean isShutdown() {
        return isShutdown((long) stateHandle.getVolatile(this));
    }

    @Override
    public boolean isTerminated() {
        return isOwnTerminated((long) stateHandle.getVolatile(this)) && view.isTerminated();
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);

        // Wait for the view's tasks first
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !view.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
            return false;
        }

        // Then wait for our scheduled tasks
        if (isOwnTerminated((long) stateHandle.getVolatile(this))) {
            return true;
        }
        final Thread current = Thread.currentThread();
        scheduledWaiters.add(current);
        try {
            while (!isOwnTerminated((long) stateHandle.getVolatile(this))) {
                remaining = deadline - System.nanoTime();
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
            scheduledWaiters.remove(current);
        }
    }

    // ==============================================================================================
    // ScheduledExecutorService methods
    // ==============================================================================================

    @Override
    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        Assert.checkNotNullParam("command", command);
        Assert.checkNotNullParam("unit", unit);
        // Atomically: check shutdown, increment inFlight
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot)) {
                throw msg.shutDownInitiated();
            }
            long newState = snapshot + IN_FLIGHT_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                break;
            }
            snapshot = witness;
        }
        try {
            final Thread submitter = Thread.currentThread();
            ScheduledRunnableWrapper wrapper = new ScheduledRunnableWrapper(command, submitter);
            ScheduledFuture<?> future = ses.schedule(wrapper, delay, unit);
            scheduledFutures.add(future);
            wrapper.completeExchange(submitter, future);
            return future;
        } finally {
            decrementInFlightAndCheckTermination();
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit) {
        Assert.checkNotNullParam("callable", callable);
        Assert.checkNotNullParam("unit", unit);
        // Atomically: check shutdown, increment inFlight
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot)) {
                throw msg.shutDownInitiated();
            }
            long newState = snapshot + IN_FLIGHT_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                break;
            }
            snapshot = witness;
        }
        try {
            final Thread submitter = Thread.currentThread();
            ScheduledCallableWrapper<V> wrapper = new ScheduledCallableWrapper<>(callable, submitter);
            ScheduledFuture<V> future = ses.schedule(wrapper, delay, unit);
            scheduledFutures.add(future);
            wrapper.completeExchange(submitter, future);
            return future;
        } finally {
            decrementInFlightAndCheckTermination();
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
        Assert.checkNotNullParam("command", command);
        Assert.checkNotNullParam("unit", unit);
        // Atomically: check shutdown, increment inFlight
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot)) {
                throw msg.shutDownInitiated();
            }
            long newState = snapshot + IN_FLIGHT_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                break;
            }
            snapshot = witness;
        }
        try {
            final Thread submitter = Thread.currentThread();
            ScheduledRunnableWrapper wrapper = new ScheduledRunnableWrapper(command, submitter);
            ScheduledFuture<?> future = ses.scheduleAtFixedRate(wrapper, initialDelay, period, unit);
            scheduledFutures.add(future);
            wrapper.completeExchange(submitter, future);
            return future;
        } finally {
            decrementInFlightAndCheckTermination();
        }
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
        Assert.checkNotNullParam("command", command);
        Assert.checkNotNullParam("unit", unit);
        // Atomically: check shutdown, increment inFlight
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            if (isShutdown(snapshot)) {
                throw msg.shutDownInitiated();
            }
            long newState = snapshot + IN_FLIGHT_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                break;
            }
            snapshot = witness;
        }
        try {
            final Thread submitter = Thread.currentThread();
            ScheduledRunnableWrapper wrapper = new ScheduledRunnableWrapper(command, submitter);
            ScheduledFuture<?> future = ses.scheduleWithFixedDelay(wrapper, initialDelay, delay, unit);
            scheduledFutures.add(future);
            wrapper.completeExchange(submitter, future);
            return future;
        } finally {
            decrementInFlightAndCheckTermination();
        }
    }

    // ==============================================================================================
    // Internal helpers
    // ==============================================================================================

    /**
     * Decrement the in-flight scheduling count and check for termination.
     */
    private void decrementInFlightAndCheckTermination() {
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            long newState = snapshot - IN_FLIGHT_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                notifyTermination(newState);
                return;
            }
            snapshot = witness;
        }
    }

    /**
     * Increment the running scheduled task count.
     */
    private void incrementRunning() {
        long snapshot = (long) stateHandle.getVolatile(this);
        for (;;) {
            long newState = snapshot + RUNNING_ONE;
            long witness = casState(snapshot, newState);
            if (witness == snapshot) {
                return;
            }
            snapshot = witness;
        }
    }

    /**
     * Decrement the running scheduled task count and check for termination.
     */
    private void decrementRunningAndCheckTermination() {
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

    @Override
    public String toString() {
        long snapshot = (long) stateHandle.getVolatile(this);
        return "ScheduledViewExecutor{view=" + view
                + ", ses=" + ses
                + ", shutdown=" + isShutdown(snapshot)
                + ", inFlight=" + getInFlight(snapshot)
                + ", runningScheduled=" + getRunning(snapshot)
                + ", scheduledFutures=" + scheduledFutures.size()
                + '}';
    }

    // ==============================================================================================
    // ScheduledRunnableWrapper
    // ==============================================================================================

    /**
     * Check whether the scheduled task should execute its payload.
     * Called after incrementing the running count.
     *
     * @return {@code true} if the task should run, {@code false} if shutdown has been requested
     */
    private boolean shouldExecuteScheduledTask() {
        return !isShutdown((long) stateHandle.getVolatile(this));
    }

    /**
     * Clean up after a scheduled task execution: remove the future from the tracking set
     * if it is done, and decrement the running count.
     *
     * @param future the scheduled future associated with the task
     */
    private void scheduledTaskComplete(final ScheduledFuture<?> future) {
        // Only remove from the tracking set when the future is truly done
        // (cancelled or completed). For repeating tasks, the future stays alive
        // across executions and should remain in the set.
        if (future.isDone()) {
            scheduledFutures.remove(future);
        }
        decrementRunningAndCheckTermination();
    }

    /**
     * Base class for scheduled task wrappers, providing the exchanger mechanism for receiving
     * the {@link ScheduledFuture} from the submitting thread.
     *
     * <p>The exchanger field is initialized with the submitting thread's {@link Thread} reference.
     * The runner and submitter exchange through this field:
     * <ul>
     *   <li>If the submitter has already swapped in the future, it is returned immediately.</li>
     *   <li>If the field still holds the submitter's thread, the runner replaces it with its own
     *       thread and parks until the submitter writes the future and unparks it.</li>
     *   <li>If the submitter's thread equals the current thread, a caller-runs policy was used
     *       by the scheduled executor service, which is not supported.</li>
     * </ul>
     *
     * <p>The exchange also serves as a barrier: by the time the runner proceeds, the submitter
     * has already added the future to the {@link #scheduledFutures} tracking set.
     */
    private abstract class ScheduledTaskBase {

        private static final VarHandle exchangeHandle = ConstantBootstraps.fieldVarHandle(
                MethodHandles.lookup(), "exchange", VarHandle.class,
                ScheduledTaskBase.class, Object.class);

        /**
         * Exchanger field for the future handoff. Initialized to the submitting thread;
         * transitions to the runner's thread (if it arrives first) or the {@link ScheduledFuture}
         * (if the submitter completes first).
         */
        @SuppressWarnings("unused") // accessed via VarHandle
        private volatile Object exchange;

        ScheduledTaskBase(final Thread submitter) {
            this.exchange = submitter;
        }

        /**
         * Wait for the submitting thread to deliver the future via the exchanger field.
         *
         * @return the scheduled future (never {@code null})
         * @throws RejectedExecutionException if a caller-runs policy is detected
         */
        final ScheduledFuture<?> awaitFuture() {
            final Thread currentThread = Thread.currentThread();
            Object val = exchangeHandle.getVolatile(this);
            if (val instanceof ScheduledFuture<?> f) {
                return f;
            }
            // val is the submitter's Thread
            if (val == currentThread) {
                throw msg.callerRunsNotSupported();
            }
            // Try to swap the submitter's thread for ours, signaling that we're waiting
            Object witness = exchangeHandle.compareAndExchange(this, val, currentThread);
            if (witness != val) {
                // Submitter raced us and already wrote the future
                return (ScheduledFuture<?>) witness;
            }
            // We wrote our thread; park until the submitter swaps in the future
            while ((val = exchangeHandle.getVolatile(this)) instanceof Thread) {
                LockSupport.park(this);
            }
            return (ScheduledFuture<?>) val;
        }

        /**
         * Complete the submitter side of the exchange. Called after {@code ses.schedule()} returns
         * and after the future has been added to the tracking set. If the runner has already
         * arrived and parked, this swaps in the future and unparks it.
         *
         * @param submitter the submitting thread (used as the expected value for CAS)
         * @param future the scheduled future to deliver to the runner
         */
        final void completeExchange(final Thread submitter, final ScheduledFuture<?> future) {
            Object witness = exchangeHandle.compareAndExchange(this, submitter, future);
            if (witness != submitter) {
                // Runner arrived first and wrote its Thread — deliver the future and unpark
                exchangeHandle.setVolatile(this, future);
                LockSupport.unpark((Thread) witness);
            }
        }
    }

    /**
     * Wraps a scheduled {@link Runnable} to participate in the running count and respect shutdown.
     */
    private final class ScheduledRunnableWrapper extends ScheduledTaskBase implements Runnable {

        private final Runnable delegate;

        ScheduledRunnableWrapper(final Runnable delegate, final Thread submitter) {
            super(submitter);
            this.delegate = delegate;
        }

        @Override
        public void run() {
            ScheduledFuture<?> future = awaitFuture();
            incrementRunning();
            try {
                if (shouldExecuteScheduledTask()) {
                    delegate.run();
                }
            } finally {
                scheduledTaskComplete(future);
            }
        }

        @Override
        public String toString() {
            return "ScheduledViewExecutor.ScheduledRunnableWrapper{" + delegate + '}';
        }
    }

    /**
     * Wraps a scheduled {@link Callable} to participate in the running count and respect shutdown.
     *
     * @param <V> the result type of the callable
     */
    private final class ScheduledCallableWrapper<V> extends ScheduledTaskBase implements Callable<V> {

        private final Callable<V> delegate;

        ScheduledCallableWrapper(final Callable<V> delegate, final Thread submitter) {
            super(submitter);
            this.delegate = delegate;
        }

        @Override
        public V call() throws Exception {
            ScheduledFuture<?> future = awaitFuture();
            incrementRunning();
            try {
                if (shouldExecuteScheduledTask()) {
                    return delegate.call();
                }
                return null;
            } finally {
                scheduledTaskComplete(future);
            }
        }

        @Override
        public String toString() {
            return "ScheduledViewExecutor.ScheduledCallableWrapper{" + delegate + '}';
        }
    }
}
