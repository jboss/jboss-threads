package org.jboss.threads.virtual;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A scheduler for a specific thread.
 */
final class UserThreadScheduler extends ThreadScheduler {
    /**
     * The current scheduling executor.
     */
    private Dispatcher dispatcher;
    /**
     * The user task.
     */
    private Runnable task;

    UserThreadScheduler(final Scheduler scheduler, final Runnable task, final long idx) {
        this(scheduler, task, scheduler.poolDispatcher(), idx);
    }

    UserThreadScheduler(final Scheduler scheduler, final Runnable task, final long idx, final EventLoopThread eventLoopThread) {
        this(scheduler, task, eventLoopThread.dispatcher(), idx);
    }

    private UserThreadScheduler(final Scheduler scheduler, final Runnable task, final Dispatcher dispatcher, final long idx) {
        super(scheduler, "user-", idx);
        this.dispatcher = dispatcher;
        this.task = task;
    }

    void runThreadBody() {
        Runnable task = this.task;
        // release the reference
        this.task = null;
        task.run();
    }

    public void execute(Runnable command) {
        if (command == Access.continuationOf(virtualThread())) {
            dispatcher.execute(this);
        } else {
            // we can only execute our continuation
            throw new IllegalStateException();
        }
    }

    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        // command is going to be VirtualThread::unpark or similar (runnable from carrier thread)
        return dispatcher.schedule(command, unit.toNanos(delay));
    }

    void resumeOn(final Dispatcher dispatcher) {
        if (dispatcher != this.dispatcher) {
            this.dispatcher = dispatcher;
            Thread.yield();
        }
    }

    void parkAndResumeOn(final Object blocker, final Dispatcher dispatcher) {
        if (dispatcher != this.dispatcher) {
            this.dispatcher = dispatcher;
            // clear yielded flag
            yielded();
            if (blocker == null) {
                LockSupport.park();
            } else {
                LockSupport.park(blocker);
            }
            if (! yielded()) {
                // park didn't block, so manually reschedule
                Thread.yield();
            }
        } else {
            if (blocker == null) {
                LockSupport.park();
            } else {
                LockSupport.park(blocker);
            }
        }
    }

    void parkNanosAndResumeOn(final Object blocker, final long nanos, final Dispatcher dispatcher) {
        if (dispatcher != this.dispatcher) {
            this.dispatcher = dispatcher;
            // todo: we have to yield now so that we don't end up calling `schedule` from the wrong thread
            // we can optimize this by examining the dispatcher or by synchronizing the queue somehow
            Thread.yield();
        }
        if (blocker == null) {
            LockSupport.parkNanos(nanos);
        } else {
            LockSupport.parkNanos(blocker, nanos);
        }
    }
}
