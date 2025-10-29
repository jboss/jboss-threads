package org.jboss.threads.virtual;

import java.util.concurrent.ScheduledFuture;

abstract class Dispatcher {
    abstract void execute(UserThreadScheduler continuation);

    abstract ScheduledFuture<?> schedule(Runnable task, long nanos);
}
