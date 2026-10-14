package org.jboss.threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link ScheduledViewExecutor}.
 */
@Timeout(10)
public class ScheduledViewExecutorTest {

    private ScheduledExecutorService ses;
    private SimpleViewExecutor view;

    @BeforeEach
    void setUp() {
        ses = Executors.newScheduledThreadPool(2);
        view = new SimpleViewExecutor(
                Executors.newCachedThreadPool(), 0, 0, false, true,
                JBossExecutors.loggingExceptionHandler());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        view.shutdown(true);
        view.awaitTermination(3, TimeUnit.SECONDS);
        ses.shutdownNow();
        ses.awaitTermination(3, TimeUnit.SECONDS);
    }

    // ==============================================================================================
    // Basic scheduling
    // ==============================================================================================

    @Test
    public void testScheduleRunnable() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        AtomicBoolean ran = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);
        sve.schedule(() -> {
            ran.set(true);
            latch.countDown();
        }, 10, TimeUnit.MILLISECONDS);
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isTrue();
    }

    @Test
    public void testScheduleCallable() throws Exception {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        ScheduledFuture<String> future = sve.schedule(() -> "hello", 10, TimeUnit.MILLISECONDS);
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo("hello");
    }

    @Test
    public void testScheduleAtFixedRate() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        AtomicInteger count = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);
        sve.scheduleAtFixedRate(() -> {
            count.incrementAndGet();
            latch.countDown();
        }, 0, 50, TimeUnit.MILLISECONDS);
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isGreaterThanOrEqualTo(3);
        sve.shutdown();
    }

    @Test
    public void testScheduleWithFixedDelay() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        AtomicInteger count = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);
        sve.scheduleWithFixedDelay(() -> {
            count.incrementAndGet();
            latch.countDown();
        }, 0, 50, TimeUnit.MILLISECONDS);
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isGreaterThanOrEqualTo(3);
        sve.shutdown();
    }

    // ==============================================================================================
    // Immediate task delegation
    // ==============================================================================================

    @Test
    public void testExecuteDelegatesToView() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        AtomicBoolean ran = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);
        sve.execute(() -> {
            ran.set(true);
            latch.countDown();
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isTrue();
    }

    // ==============================================================================================
    // Shutdown
    // ==============================================================================================

    @Test
    public void testShutdownRejectsNewScheduledTasks() {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        sve.shutdown();
        assertThat(sve.isShutdown()).isTrue();
        assertThatThrownBy(() -> sve.schedule(NullRunnable.getInstance(), 1, TimeUnit.SECONDS))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    public void testShutdownRejectsNewImmediateTasks() {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        sve.shutdown();
        assertThatThrownBy(() -> sve.execute(NullRunnable.getInstance()))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    public void testShutdownCancelsUnfiredTasks() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        AtomicBoolean ran = new AtomicBoolean();
        // Schedule far in the future
        ScheduledFuture<?> future = sve.schedule(() -> ran.set(true), 1, TimeUnit.HOURS);
        sve.shutdown();
        // The future should have been cancelled
        assertThat(future.isCancelled()).isTrue();
        assertThat(ran.get()).isFalse();
        assertThat(sve.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void testShutdownNowCancelsUnfiredTasks() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        AtomicBoolean ran = new AtomicBoolean();
        sve.schedule(() -> ran.set(true), 1, TimeUnit.HOURS);
        sve.shutdownNow();
        assertThat(ran.get()).isFalse();
        assertThat(sve.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    // ==============================================================================================
    // awaitTermination waits for running scheduled tasks
    // ==============================================================================================

    @Test
    public void testAwaitTerminationWaitsForRunningScheduledTask() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        sve.schedule(() -> {
            taskStarted.countDown();
            try {
                taskRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0, TimeUnit.MILLISECONDS);

        assertThat(taskStarted.await(3, TimeUnit.SECONDS)).isTrue();
        sve.shutdown();

        // Should not terminate yet — task is still running
        assertThat(sve.awaitTermination(50, TimeUnit.MILLISECONDS)).isFalse();

        taskRelease.countDown();
        assertThat(sve.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        assertThat(sve.isTerminated()).isTrue();
    }

    // ==============================================================================================
    // Termination requires both view and scheduled to be done
    // ==============================================================================================

    @Test
    public void testTerminationRequiresBothViewAndScheduledDone() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        CountDownLatch immediateStarted = new CountDownLatch(1);
        CountDownLatch immediateRelease = new CountDownLatch(1);

        // Submit an immediate task via the view
        sve.execute(() -> {
            immediateStarted.countDown();
            try {
                immediateRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(immediateStarted.await(3, TimeUnit.SECONDS)).isTrue();

        sve.shutdown();
        // View task is still running
        assertThat(sve.isTerminated()).isFalse();
        assertThat(sve.awaitTermination(50, TimeUnit.MILLISECONDS)).isFalse();

        immediateRelease.countDown();
        assertThat(sve.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        assertThat(sve.isTerminated()).isTrue();
    }

    // ==============================================================================================
    // Shutdown with no tasks
    // ==============================================================================================

    @Test
    public void testShutdownWithNoTasks() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        sve.shutdown();
        assertThat(sve.isShutdown()).isTrue();
        assertThat(sve.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        assertThat(sve.isTerminated()).isTrue();
    }

    // ==============================================================================================
    // Repeating task shutdown
    // ==============================================================================================

    @Test
    public void testRepeatingTaskCancelledOnShutdown() throws InterruptedException {
        ScheduledViewExecutor sve = new ScheduledViewExecutor(view, ses);
        AtomicInteger count = new AtomicInteger();
        ScheduledFuture<?> future = sve.scheduleAtFixedRate(count::incrementAndGet,
                0, 20, TimeUnit.MILLISECONDS);
        // Let it run a few times
        Thread.sleep(100);
        sve.shutdown();
        assertThat(future.isCancelled()).isTrue();
        int countAtShutdown = count.get();
        assertThat(countAtShutdown).isGreaterThan(0);
        assertThat(sve.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        // No more executions after shutdown
        Thread.sleep(100);
        assertThat(count.get()).isEqualTo(countAtShutdown);
    }
}
