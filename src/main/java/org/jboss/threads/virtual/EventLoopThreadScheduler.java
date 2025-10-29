package org.jboss.threads.virtual;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The thread scheduler for an event loop thread.
 */
final class EventLoopThreadScheduler extends ThreadScheduler {
    private static final VarHandle waitTimeHandle = ConstantBootstraps.fieldVarHandle(MethodHandles.lookup(), "waitTime", VarHandle.class, EventLoopThreadScheduler.class, long.class);

    private final EventLoopThread eventLoopThread;
    /**
     * The wait time for the virtual thread side of the event loop.
     * This value is handed back and forth between the I/O carrier thread and the event loop virtual thread.
     */
    @SuppressWarnings("unused") // waitTimeHandle
    private volatile long waitTime = -1;

    EventLoopThreadScheduler(final Scheduler scheduler, final EventLoopThread eventLoopThread, final long idx) {
        super(scheduler, "event-loop-", idx);
        this.eventLoopThread = eventLoopThread;
        // set event loop thread to almost-maximum priority
        setPriority(Thread.MAX_PRIORITY - 1);
    }

    void runThreadBody() {
        // this runs on the *virtual* event loop thread
        EventLoopThread eventLoopThread = this.eventLoopThread;
        final EventLoop eventLoop = eventLoopThread.eventLoop();
        long waitTime;
        for (;;) {
            // clear the unpark permit
            Util.clearUnpark();
            // clear interrupt status
            Thread.interrupted();
            // call the event loop
            waitTime = (long) waitTimeHandle.getOpaque(this);
            try {
                eventLoop.unparkAny(waitTime);
            } catch (Throwable ignored) {
            }
            // avoid starvation
            if (! yielded()) {
                Thread.yield();
                // yielding sets the flag to true, so clear it again
                yielded();
            }
        }
    }

    void setWaitTime(long nanos) {
        waitTimeHandle.setOpaque(this, nanos);
    }

    public void execute(final Runnable command) {
        eventLoopThread.enqueue(this);
    }

    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        return eventLoopThread.schedule(command, unit.convert(delay, TimeUnit.NANOSECONDS));
    }
}
