package org.jboss.threads.virtual;

import java.util.concurrent.locks.LockSupport;

import io.smallrye.common.annotation.Experimental;

/**
 * An event loop for a virtual thread scheduler.
 * There will be one instance per I/O thread within an event loop group.
 */
@Experimental("Experimental virtual thread support")
public abstract class EventLoop {
    /**
     * Construct a new instance.
     */
    protected EventLoop() {}

    /**
     * Unpark all ready threads and return,
     * possibly waiting for some amount of time if no threads are ready.
     * The wait time may be {@code 0}, in which case this method should return immediately if no threads are ready,
     * or {@code -1}, in which case the method should wait indefinitely for threads to become ready.
     * Otherwise, the wait time is the maximum number of nanoseconds to wait for threads to become ready before returning.
     * <p>
     * Regardless of the wait time, the method should park or return immediately if the {@link #wakeup()} method is invoked
     * from any thread.
     * <p>
     * This method will be called in a loop (the event loop, in fact).
     * After each invocation of this method, up to one other waiting thread will be continued.
     * Since this generally would lead to busy-looping,
     * the implementation of this method <em>should</em>
     * {@linkplain VirtualThreads#yieldNanos(long) yield for some amount of time} before returning to allow other threads to run.
     * <p>
     * Note that {@linkplain Thread#sleep(long) sleeping} or {@linkplain LockSupport#park() parking} may cause latency spikes,
     * so it is not recommended.
     * <p>
     * This method should only be called from the event loop virtual thread.
     *
     * @param waitTime {@code 0} to return immediately after unparking any ready threads (even if there are none),
     *    {@code -1} unpark any ready threads or to wait indefinitely for a thread to become ready,
     *    or any positive integer to unpark any ready threads or to wait for no more than that number of nanoseconds
     * @throws InterruptedException if some interruptible operation was interrupted
     */
    protected abstract void unparkAny(long waitTime) throws InterruptedException;

    /**
     * Forcibly awaken the event loop, if it is currently blocked in {@link #unparkAny(long)}.
     * This method may be called from any thread.
     */
    protected abstract void wakeup();
}
