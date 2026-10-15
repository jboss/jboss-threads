package org.jboss.threads;

import io.smallrye.common.constraint.Assert;

import java.security.PrivilegedAction;
import java.util.OptionalInt;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;

import static java.security.AccessController.doPrivileged;

/**
 * An executor service that is actually a "view" over another executor service.
 */
public abstract class ViewExecutor extends AbstractExecutorService {

    private volatile Thread.UncaughtExceptionHandler handler;
    private volatile Runnable terminationTask;

    // Intentionally package private to effectively seal the type.
    ViewExecutor() {}

    @Override
    public final void shutdown() {
        shutdown(false);
    }

    /**
     * Initiates an orderly shutdown, optionally interrupting running tasks.
     * Previously submitted tasks continue to execute. No new tasks will be accepted.
     *
     * @param interrupt {@code true} to interrupt running tasks
     */
    public abstract void shutdown(boolean interrupt);

    /**
     * Get the uncaught exception handler used by this executor.
     *
     * @return the exception handler, or {@code null} if none is set
     */
    public final Thread.UncaughtExceptionHandler getExceptionHandler() {
        return handler;
    }

    /**
     * Set the uncaught exception handler used by this executor.
     *
     * @param handler the exception handler (must not be {@code null})
     */
    public final void setExceptionHandler(final Thread.UncaughtExceptionHandler handler) {
        Assert.checkNotNullParam("handler", handler);
        this.handler = handler;
    }

    /**
     * Get the termination task that is run when the executor terminates.
     *
     * @return the termination task, or {@code null} if none is set
     */
    public final Runnable getTerminationTask() {
        return terminationTask;
    }

    /**
     * Set the termination task that is run when the executor terminates.
     *
     * @param terminationTask the termination task, or {@code null} to clear
     */
    public final void setTerminationTask(final Runnable terminationTask) {
        this.terminationTask = terminationTask;
    }

    /**
     * Create a new builder for a view executor that delegates to the given executor.
     *
     * @param delegate the delegate executor (must not be {@code null})
     * @return the new builder
     */
    public static Builder builder(Executor delegate) {
        Assert.checkNotNullParam("delegate", delegate);
        return new Builder(delegate);
    }

    /**
     * A builder for {@link ViewExecutor} instances. The builder selects the appropriate
     * implementation based on the configured parameters:
     *
     * <ul>
     *   <li>If a finite {@linkplain #parallelism(int) parallelism} limit is set, an
     *       {@link EnhancedViewExecutor} is built, which uses a physical task queue to enforce
     *       the parallelism constraint.</li>
     *   <li>Otherwise, a {@link SimpleViewExecutor} is built, which delegates all tasks
     *       immediately to the underlying executor and uses atomic counters for admission control.</li>
     * </ul>
     *
     * <p>Three interdependent limits can be configured: {@linkplain #parallelism(int) parallelism},
     * {@linkplain #queueLimit(int) queue limit}, and {@linkplain #capacityLimit(int) capacity limit}.
     * When parallelism is finite, these are related by the constraint
     * {@code capacityLimit == parallelism + queueLimit}. At most two of the three should be set
     * explicitly; the third is derived at {@linkplain #build() build time}. If all three are set,
     * they are validated for consistency.
     */
    public static final class Builder {
        private static final int UNSET = -1;

        private final Executor delegate;
        private int parallelism = 1;
        private int queueLimit = UNSET;
        private int capacityLimit = UNSET;
        private boolean returnTasksOnShutdownNow = false;
        private boolean supportInterruptOnShutdownNow = false;
        private Thread.UncaughtExceptionHandler handler = JBossExecutors.loggingExceptionHandler();

        Builder(final Executor delegate) {
            this.delegate = delegate;
        }

        // ==================== Parallelism ====================

        /**
         * Set a finite parallelism limit. When set, the built executor will use a physical task queue
         * to ensure that at most {@code parallelism} tasks execute concurrently. Excess tasks are
         * queued up to the {@linkplain #queueLimit(int) queue limit}.
         *
         * <p>Setting a finite parallelism requires the use of {@link EnhancedViewExecutor}.
         *
         * @param parallelism the maximum number of concurrently executing tasks (must be at least 1)
         * @return this builder
         */
        public Builder parallelism(final int parallelism) {
            Assert.checkMinimumParameter("parallelism", 1, parallelism);
            this.parallelism = parallelism;
            return this;
        }

        /**
         * Remove the parallelism limit. The delegate executor controls how many tasks run
         * concurrently.
         *
         * @return this builder
         */
        public Builder unlimitedParallelism() {
            this.parallelism = UNSET;
            return this;
        }

        /**
         * Get the configured parallelism limit, if any.
         *
         * @return the parallelism limit, or empty if unlimited
         */
        public OptionalInt parallelism() {
            int val = this.parallelism;
            return val == UNSET ? OptionalInt.empty() : OptionalInt.of(val);
        }

        // ==================== Queue Limit ====================

        /**
         * Set a finite queue limit. The meaning depends on the implementation:
         *
         * <ul>
         *   <li>When parallelism is finite ({@link EnhancedViewExecutor}): the maximum number of
         *       tasks that can wait in the physical queue for a parallelism slot.</li>
         *   <li>When parallelism is unlimited ({@link SimpleViewExecutor}): the maximum number of
         *       submitted-but-not-started tasks (tasks dispatched to the delegate but whose
         *       {@code run()} method has not yet been called).</li>
         * </ul>
         *
         * @param queueLimit the maximum number of waiting tasks (must be at least 0)
         * @return this builder
         */
        public Builder queueLimit(final int queueLimit) {
            Assert.checkMinimumParameter("queueLimit", 0, queueLimit);
            this.queueLimit = queueLimit;
            return this;
        }

        /**
         * Remove the queue limit. This is the default.
         *
         * @return this builder
         */
        public Builder unlimitedQueueLimit() {
            this.queueLimit = UNSET;
            return this;
        }

        /**
         * Get the configured queue limit, if any.
         *
         * @return the queue limit, or empty if unlimited
         */
        public OptionalInt queueLimit() {
            int val = this.queueLimit;
            return val == UNSET ? OptionalInt.empty() : OptionalInt.of(val);
        }

        // ==================== Capacity Limit ====================

        /**
         * Set a finite capacity limit. This is the maximum total number of outstanding tasks
         * (both waiting and running). When parallelism is finite, this is equivalent to
         * {@code parallelism + queueLimit}.
         *
         * @param capacityLimit the maximum total outstanding tasks (must be at least 1)
         * @return this builder
         */
        public Builder capacityLimit(final int capacityLimit) {
            Assert.checkMinimumParameter("capacityLimit", 1, capacityLimit);
            this.capacityLimit = capacityLimit;
            return this;
        }

        /**
         * Remove the capacity limit. This is the default.
         *
         * @return this builder
         */
        public Builder unlimitedCapacityLimit() {
            this.capacityLimit = UNSET;
            return this;
        }

        /**
         * Get the configured capacity limit, if any.
         *
         * @return the capacity limit, or empty if unlimited
         */
        public OptionalInt capacityLimit() {
            int val = this.capacityLimit;
            return val == UNSET ? OptionalInt.empty() : OptionalInt.of(val);
        }

        // ==================== Tracking flags ====================

        /**
         * Configure whether {@link java.util.concurrent.ExecutorService#shutdownNow()} is
         * guaranteed to return the list of tasks that were waiting to execute. When {@code true},
         * a tracking set is maintained for submitted-but-not-started tasks, which adds a small
         * per-task overhead. When {@code false}, the behavior is implementation-dependent:
         * {@code shutdownNow()} may or may not return waiting tasks.
         *
         * <p>The default is {@code false}.
         *
         * @param enable {@code true} to guarantee that waiting tasks are returned
         * @return this builder
         */
        public Builder returnTasksOnShutdownNow(final boolean enable) {
            this.returnTasksOnShutdownNow = enable;
            return this;
        }

        /**
         * Get whether {@code shutdownNow()} is guaranteed to return waiting tasks.
         *
         * @return {@code true} if guaranteed
         */
        public boolean returnTasksOnShutdownNow() {
            return returnTasksOnShutdownNow;
        }

        /**
         * Configure whether {@link java.util.concurrent.ExecutorService#shutdownNow()} is
         * guaranteed to support interrupting running tasks. When {@code true}, a tracking set
         * is maintained for currently-running tasks, which adds a small per-task overhead.
         * When {@code false}, the behavior is implementation-dependent: {@code shutdownNow()}
         * may or may not interrupt running tasks.
         *
         * <p>The default is {@code false}.
         *
         * @param enable {@code true} to guarantee interrupt support
         * @return this builder
         */
        public Builder supportInterruptOnShutdownNow(final boolean enable) {
            this.supportInterruptOnShutdownNow = enable;
            return this;
        }

        /**
         * Get whether {@code shutdownNow()} is guaranteed to support interrupting running tasks.
         *
         * @return {@code true} if guaranteed
         */
        public boolean supportInterruptOnShutdownNow() {
            return supportInterruptOnShutdownNow;
        }

        // ==================== Exception handler ====================

        /**
         * Set the uncaught exception handler for the built executor.
         *
         * @param handler the exception handler (must not be {@code null})
         * @return this builder
         */
        public Builder uncaughtHandler(final Thread.UncaughtExceptionHandler handler) {
            Assert.checkNotNullParam("handler", handler);
            this.handler = handler;
            return this;
        }

        /**
         * Get the configured uncaught exception handler.
         *
         * @return the exception handler (never {@code null})
         */
        public Thread.UncaughtExceptionHandler uncaughtHandler() {
            return handler;
        }

        // ==================== Deprecated API ====================

        /**
         * Get the delegate executor.
         *
         * @return the delegate executor
         */
        public Executor delegate() {
            return delegate;
        }

        /**
         * Set the maximum number of concurrently executing tasks.
         *
         * @param maxSize the maximum concurrency (must be at least 1)
         * @return this builder
         * @deprecated Use {@link #parallelism(int)} instead.
         */
        @Deprecated
        public Builder setMaxSize(final int maxSize) {
            return parallelism(maxSize);
        }

        /**
         * Get the maximum number of concurrently executing tasks.
         *
         * @return the maximum concurrency
         * @throws IllegalStateException if parallelism is unlimited
         * @deprecated Use {@link #parallelism()} instead.
         */
        @Deprecated
        public int getMaxSize() {
            int val = this.parallelism;
            if (val == UNSET) {
                throw new IllegalStateException("Parallelism is unlimited");
            }
            return val;
        }

        /**
         * Set the maximum number of tasks that can wait in the queue.
         *
         * @param queueLimit the queue limit (must be at least 0)
         * @return this builder
         * @deprecated Use {@link #queueLimit(int)} instead.
         */
        @Deprecated
        public Builder setQueueLimit(final int queueLimit) {
            return queueLimit(queueLimit);
        }

        /**
         * Get the maximum number of tasks that can wait in the queue.
         *
         * @return the queue limit, or {@link Integer#MAX_VALUE} if unlimited
         * @deprecated Use {@link #queueLimit()} instead.
         */
        @Deprecated
        public int getQueueLimit() {
            int val = this.queueLimit;
            return val == UNSET ? Integer.MAX_VALUE : val;
        }

        /**
         * Get the delegate executor.
         *
         * @return the delegate executor
         * @deprecated Use {@link #delegate()} instead.
         */
        @Deprecated
        public Executor getDelegate() {
            return delegate;
        }

        /**
         * Set the uncaught exception handler.
         *
         * @param handler the exception handler
         * @return this builder
         * @deprecated Use {@link #uncaughtHandler(Thread.UncaughtExceptionHandler)} instead.
         */
        @Deprecated
        public Builder setUncaughtHandler(final Thread.UncaughtExceptionHandler handler) {
            return uncaughtHandler(handler);
        }

        /**
         * Get the uncaught exception handler.
         *
         * @return the exception handler
         * @deprecated Use {@link #uncaughtHandler()} instead.
         */
        @Deprecated
        public Thread.UncaughtExceptionHandler getUncaughtHandler() {
            return uncaughtHandler();
        }

        /**
         * @deprecated This value no longer has any impact.
         */
        @Deprecated
        public int getQueueInitialSize() {
            return 0;
        }

        /**
         * @deprecated This option no longer has any impact.
         */
        @Deprecated
        public Builder setQueueInitialSize(@SuppressWarnings("unused") final int queueInitialSize) {
            return this;
        }

        // ==================== Build ====================

        /**
         * Build the view executor. The implementation is selected based on the configured parameters:
         *
         * <ul>
         *   <li>If {@linkplain #parallelism(int) parallelism} is finite, an {@link EnhancedViewExecutor}
         *       is built with the configured parallelism and queue limit.</li>
         *   <li>Otherwise, a {@link SimpleViewExecutor} is built with the configured queue limit
         *       and capacity limit.</li>
         * </ul>
         *
         * <p>When parallelism is finite, at most two of {parallelism, queueLimit, capacityLimit}
         * should be set; the third is derived. If all three are set, they must satisfy
         * {@code capacityLimit == parallelism + queueLimit}.
         *
         * @return the new view executor
         * @throws IllegalArgumentException if the configured limits are inconsistent
         */
        public ViewExecutor build() {
            final Executor delegate = Assert.checkNotNullParam("delegate", this.delegate);
            final int p = this.parallelism;
            final int ql = this.queueLimit;
            final int cl = this.capacityLimit;
            if (p != UNSET) {
                // Finite parallelism → EnhancedViewExecutor
                final int q;
                if (ql != UNSET && cl != UNSET) {
                    // All three set — validate consistency
                    q = ql;
                    if (cl != p + q) {
                        throw new IllegalArgumentException(
                                "capacityLimit (" + cl + ") must equal parallelism (" + p
                                        + ") + queueLimit (" + q + ")");
                    }
                } else if (cl != UNSET) {
                    // Derive queue limit from capacity - parallelism
                    if (cl < p) {
                        throw new IllegalArgumentException(
                                "capacityLimit (" + cl + ") must be >= parallelism (" + p + ")");
                    }
                    q = cl - p;
                } else {
                    // Use queue limit directly, or default to MAX_VALUE
                    q = ql == UNSET ? Integer.MAX_VALUE : ql;
                }
                return new EnhancedViewExecutor(delegate, p, q, handler);
            } else {
                // Unlimited parallelism → SimpleViewExecutor
                final int q = ql == UNSET ? 0 : ql;
                final int c = cl == UNSET ? 0 : cl;
                return new SimpleViewExecutor(
                        delegate, q, c,
                        returnTasksOnShutdownNow,
                        supportInterruptOnShutdownNow,
                        handler);
            }
        }
    }

    /**
     * Run the termination task, if one is set. Called when the executor reaches
     * the terminated state. Exceptions thrown by the termination task are reported
     * to the uncaught exception handler.
     */
    protected void runTermination() {
        final Runnable task = ViewExecutor.this.terminationTask;
        ViewExecutor.this.terminationTask = null;
        if (task != null) try {
            task.run();
        } catch (Throwable t) {
            Thread.UncaughtExceptionHandler configuredHandler = handler;
            if (configuredHandler != null) {
                try {
                    handler.uncaughtException(Thread.currentThread(), t);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static int readIntPropertyPrefixed(String name, int defVal) {
        try {
            return Integer.parseInt(readPropertyPrefixed(name, Integer.toString(defVal)));
        } catch (NumberFormatException ignored) {
            return defVal;
        }
    }

    static String readPropertyPrefixed(String name, String defVal) {
        return readProperty("org.jboss.threads.view-executor." + name, defVal);
    }

    static String readProperty(String name, String defVal) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            return doPrivileged((PrivilegedAction<String>) () -> readPropertyRaw(name, defVal));
        } else {
            return readPropertyRaw(name, defVal);
        }
    }

    static String readPropertyRaw(final String name, final String defVal) {
        return System.getProperty(name, defVal);
    }
}
