package org.jboss.threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link SimpleViewExecutor} and the {@link ViewExecutor.Builder} integration.
 */
public class SimpleViewExecutorTest {

    // ==============================================================================================
    // Basic execution
    // ==============================================================================================

    @Test
    public void testBasicExecution() throws InterruptedException {
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            ViewExecutor view = ViewExecutor.builder(delegate)
                    .unlimitedParallelism()
                    .build();
            AtomicBoolean ran = new AtomicBoolean();
            CountDownLatch latch = new CountDownLatch(1);
            view.execute(() -> {
                ran.set(true);
                latch.countDown();
            });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(ran.get()).isTrue();
            view.shutdown();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    @Test
    public void testDirectExecutor() {
        ViewExecutor view = ViewExecutor.builder(Runnable::run)
                .unlimitedParallelism()
                .build();
        AtomicBoolean ran = new AtomicBoolean();
        view.execute(() -> ran.set(true));
        assertThat(ran.get()).isTrue();
        view.shutdown();
        assertThat(view.isTerminated()).isTrue();
    }

    // ==============================================================================================
    // Counter tracking
    // ==============================================================================================

    @Test
    public void testCounterTracking() throws InterruptedException {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            SimpleViewExecutor view = new SimpleViewExecutor(
                    delegate, 0, 0, false, false,
                    JBossExecutors.loggingExceptionHandler());
            assertThat(view.getSubmittedCount()).isZero();
            assertThat(view.getRunningCount()).isZero();

            view.execute(() -> {
                taskStarted.countDown();
                try {
                    taskRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(taskStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(view.getRunningCount()).isEqualTo(1);
            assertThat(view.getSubmittedCount()).isZero();

            taskRelease.countDown();
            view.shutdown();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(view.getRunningCount()).isZero();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // Queue limit
    // ==============================================================================================

    @Test
    public void testQueueLimit() throws InterruptedException {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            ViewExecutor delayedView = ViewExecutor.builder(
                    command -> delegate.execute(() -> {
                        taskStarted.countDown();
                        try {
                            taskRelease.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        command.run();
                    }))
                    .unlimitedParallelism()
                    .queueLimit(2)
                    .build();

            delayedView.execute(NullRunnable.getInstance());
            delayedView.execute(NullRunnable.getInstance());

            assertThatThrownBy(() -> delayedView.execute(NullRunnable.getInstance()))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("Queue limit");

            taskStarted.await(3, TimeUnit.SECONDS);
            taskRelease.countDown();
            delayedView.shutdown();
            assertThat(delayedView.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // Capacity limit
    // ==============================================================================================

    @Test
    public void testCapacityLimit() throws InterruptedException {
        CountDownLatch tasksStarted = new CountDownLatch(2);
        CountDownLatch taskRelease = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            ViewExecutor view = ViewExecutor.builder(delegate)
                    .unlimitedParallelism()
                    .capacityLimit(2)
                    .build();

            for (int i = 0; i < 2; i++) {
                view.execute(() -> {
                    tasksStarted.countDown();
                    try {
                        taskRelease.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThat(tasksStarted.await(3, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> view.execute(NullRunnable.getInstance()))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("Capacity limit");

            taskRelease.countDown();
            view.shutdown();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // Shutdown
    // ==============================================================================================

    @Test
    public void testShutdownRejectsNewTasks() {
        ViewExecutor view = ViewExecutor.builder(Runnable::run)
                .unlimitedParallelism()
                .build();
        view.shutdown();
        assertThat(view.isShutdown()).isTrue();
        assertThatThrownBy(() -> view.execute(NullRunnable.getInstance()))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    public void testShutdownWithNoTasks() {
        ViewExecutor view = ViewExecutor.builder(Runnable::run)
                .unlimitedParallelism()
                .build();
        view.shutdown();
        assertThat(view.isShutdown()).isTrue();
        assertThat(view.isTerminated()).isTrue();
    }

    @Test
    @Timeout(5)
    public void testShutdownWaitsForRunningTasks() throws InterruptedException {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            ViewExecutor view = ViewExecutor.builder(delegate)
                    .unlimitedParallelism()
                    .build();
            view.execute(() -> {
                taskStarted.countDown();
                try {
                    taskRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            taskStarted.await();
            view.shutdown();
            assertThat(view.isShutdown()).isTrue();
            assertThat(view.isTerminated()).isFalse();

            assertThat(view.awaitTermination(50, TimeUnit.MILLISECONDS)).isFalse();

            taskRelease.countDown();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(view.isTerminated()).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // Interrupt support
    // ==============================================================================================

    @Test
    @Timeout(5)
    public void testShutdownWithInterrupt() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskDone = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            ViewExecutor view = ViewExecutor.builder(delegate)
                    .unlimitedParallelism()
                    .supportInterruptOnShutdownNow(true)
                    .build();
            view.execute(() -> {
                taskStarted.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } finally {
                    taskDone.countDown();
                }
            });
            taskStarted.await();
            view.shutdown(true);
            assertThat(taskDone.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.get()).isTrue();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    @Test
    @Timeout(5)
    public void testShutdownNowInterrupts() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskDone = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            ViewExecutor view = ViewExecutor.builder(delegate)
                    .unlimitedParallelism()
                    .supportInterruptOnShutdownNow(true)
                    .build();
            view.execute(() -> {
                taskStarted.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } finally {
                    taskDone.countDown();
                }
            });
            taskStarted.await();
            List<Runnable> neverRan = view.shutdownNow();
            assertThat(neverRan).isEmpty();
            assertThat(taskDone.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.get()).isTrue();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // shutdownNow with submitted task tracking
    // ==============================================================================================

    @Test
    @Timeout(5)
    public void testShutdownNowReturnsSubmittedTasks() throws InterruptedException {
        CountDownLatch releaseGate = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            AtomicInteger startedCount = new AtomicInteger();
            ViewExecutor view = ViewExecutor.builder(
                    command -> delegate.execute(() -> {
                        startedCount.incrementAndGet();
                        try {
                            releaseGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        command.run();
                    }))
                    .unlimitedParallelism()
                    .returnTasksOnShutdownNow(true)
                    .supportInterruptOnShutdownNow(true)
                    .build();

            view.execute(() -> {});
            view.execute(() -> {});

            Awaitility.waitAtMost(3, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(startedCount.get()).isEqualTo(2));

            view.shutdownNow();
            releaseGate.countDown();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // Limit reconfiguration
    // ==============================================================================================

    @Test
    public void testLimitReconfiguration() throws InterruptedException {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        ExecutorService delegate = Executors.newCachedThreadPool();
        try {
            SimpleViewExecutor view = new SimpleViewExecutor(
                    delegate, 0, 1, false, false,
                    JBossExecutors.loggingExceptionHandler());

            view.execute(() -> {
                taskStarted.countDown();
                try {
                    taskRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            taskStarted.await();

            assertThatThrownBy(() -> view.execute(NullRunnable.getInstance()))
                    .isInstanceOf(RejectedExecutionException.class);

            view.setLimits(0, 2);
            assertThat(view.getCapacityLimit()).isEqualTo(2);

            CountDownLatch secondStarted = new CountDownLatch(1);
            view.execute(() -> {
                secondStarted.countDown();
                try {
                    taskRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(secondStarted.await(3, TimeUnit.SECONDS)).isTrue();

            taskRelease.countDown();
            view.shutdown();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // Termination task
    // ==============================================================================================

    @Test
    public void testTerminationTask() {
        AtomicBoolean terminationRan = new AtomicBoolean();
        ViewExecutor view = ViewExecutor.builder(Runnable::run)
                .unlimitedParallelism()
                .build();
        view.setTerminationTask(() -> terminationRan.set(true));
        view.shutdown();
        assertThat(terminationRan.get()).isTrue();
        assertThat(view.isTerminated()).isTrue();
    }

    // ==============================================================================================
    // Delegate rejection
    // ==============================================================================================

    @Test
    public void testDelegateRejectionRollsBackCounter() {
        SimpleViewExecutor view = new SimpleViewExecutor(
                ignored -> { throw new RejectedExecutionException("delegate full"); },
                0, 0, false, false,
                JBossExecutors.loggingExceptionHandler());
        assertThatThrownBy(() -> view.execute(NullRunnable.getInstance()))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("delegate full");
        assertThat(view.getSubmittedCount()).isZero();
        assertThat(view.getRunningCount()).isZero();
    }

    // ==============================================================================================
    // Context class loader propagation
    // ==============================================================================================

    @Test
    public void testContextClassLoaderPropagation() throws InterruptedException {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            ViewExecutor view = ViewExecutor.builder(delegate)
                    .unlimitedParallelism()
                    .build();
            ClassLoader submitterCcl = Thread.currentThread().getContextClassLoader();
            AtomicBoolean cclMatch = new AtomicBoolean();
            CountDownLatch latch = new CountDownLatch(1);
            view.execute(() -> {
                cclMatch.set(Thread.currentThread().getContextClassLoader() == submitterCcl);
                latch.countDown();
            });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cclMatch.get()).isTrue();
            view.shutdown();
            assertThat(view.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            delegate.shutdown();
        }
    }

    // ==============================================================================================
    // Builder resolution tests
    // ==============================================================================================

    @Test
    public void testBuilderDefaultProducesEnhancedViewExecutor() {
        ViewExecutor view = ViewExecutor.builder(Runnable::run).build();
        assertThat(view).isInstanceOf(EnhancedViewExecutor.class);
        view.shutdown();
    }

    @Test
    public void testBuilderUnlimitedParallelismProducesSimpleViewExecutor() {
        ViewExecutor view = ViewExecutor.builder(Runnable::run)
                .unlimitedParallelism()
                .build();
        assertThat(view).isInstanceOf(SimpleViewExecutor.class);
        view.shutdown();
    }

    @Test
    public void testBuilderFiniteParallelismProducesEnhancedViewExecutor() {
        ViewExecutor view = ViewExecutor.builder(Runnable::run)
                .parallelism(4)
                .build();
        assertThat(view).isInstanceOf(EnhancedViewExecutor.class);
        view.shutdown();
    }

    @Test
    public void testBuilderDerivesQueueLimitFromCapacityAndParallelism() {
        ViewExecutor.Builder b = ViewExecutor.builder(Runnable::run)
                .parallelism(10)
                .capacityLimit(110);
        assertThat(b.parallelism()).hasValue(10);
        assertThat(b.capacityLimit()).hasValue(110);
        ViewExecutor view = b.build();
        assertThat(view).isInstanceOf(EnhancedViewExecutor.class);
        view.shutdown();
    }

    @Test
    public void testBuilderRejectsInconsistentLimits() {
        assertThatThrownBy(() ->
                ViewExecutor.builder(Runnable::run)
                        .parallelism(10)
                        .queueLimit(100)
                        .capacityLimit(50)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testBuilderRejectsCapacityLessThanParallelism() {
        assertThatThrownBy(() ->
                ViewExecutor.builder(Runnable::run)
                        .parallelism(10)
                        .capacityLimit(5)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testBuilderDeprecatedSetMaxSize() {
        @SuppressWarnings("deprecation")
        ViewExecutor.Builder b = ViewExecutor.builder(Runnable::run).setMaxSize(5);
        assertThat(b.parallelism()).hasValue(5);
        ViewExecutor view = b.build();
        assertThat(view).isInstanceOf(EnhancedViewExecutor.class);
        view.shutdown();
    }

    @Test
    public void testBuilderDeprecatedGetMaxSizeThrowsWhenUnlimited() {
        @SuppressWarnings("deprecation")
        ViewExecutor.Builder b = ViewExecutor.builder(Runnable::run).unlimitedParallelism();
        assertThatThrownBy(b::getMaxSize).isInstanceOf(IllegalStateException.class);
    }
}
