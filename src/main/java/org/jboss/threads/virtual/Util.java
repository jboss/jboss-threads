package org.jboss.threads.virtual;

import java.util.concurrent.locks.LockSupport;

/**
 * Utilities.
 */
final class Util {
    private Util() {}

    static void clearUnpark() {
        // change unpark permit from (0 or 1) to (1)
        LockSupport.unpark(Thread.currentThread());
        // change unpark permit from (1) to (0)
        LockSupport.park();
    }
}
