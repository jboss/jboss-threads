package org.jboss.threads;

import static java.lang.invoke.MethodHandles.*;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@MessageLogger(projectCode = "JBTHR", length = 5)
interface Messages extends BasicLogger {
    Messages msg = Logger.getMessageLogger(lookup(), Messages.class, "org.jboss.threads");
    Messages intMsg = Logger.getMessageLogger(lookup(), Messages.class, "org.jboss.threads.interrupt-handler");

    // version
    @Message(value = "JBoss Threads version %s")
    @LogMessage(level = Logger.Level.INFO)
    void version(String version);

    // execution

//    @Message(id = 1, value = "Thread factory did not produce a thread")

//    @Message(id = 2, value = "Task limit reached")

    @Message(id = 3, value = "Operation timed out")
    TimeoutException operationTimedOut();

    @Message(id = 4, value = "Operation was cancelled")
    CancellationException operationCancelled();

    @Message(id = 5, value = "Operation failed")
    ExecutionException operationFailed(@Cause Throwable cause);

//    @Message(id = 6, value = "Unable to add new thread to the running set")

    // @Message(id = 7, value = "Task execution interrupted")

//    @Message(id = 8, value = "Task rejected")

    @Message(id = 9, value = "Executor has been shut down")
    StoppedExecutorException shutDownInitiated();

    // @Message(id = 10, value = "Task execution timed out")

//    @Message(id = 11, value = "Task execution failed for task %s")

    @Message(id = 12, value = "Cannot await termination of a thread pool from one of its own threads")
    IllegalStateException cannotAwaitWithin();

//    @Message(id = 13, value = "No executors available to run task")

//    @Message(id = 14, value = "Error submitting task %s to executor")

    @Message(id = 15, value = "Queue limit reached")
    RejectedExecutionException queueLimitReached();

    @Message(id = 16, value = "Capacity limit reached")
    RejectedExecutionException capacityLimitReached();

    @Message(id = 17, value = "Capacity limit must be greater than or equal to queue limit when capacity limit is non-zero")
    IllegalArgumentException capacityLimitMustBeGreaterThanQueueLimit();

    @Message(id = 18, value = "Scheduled executor service used a caller-runs policy, which is not supported")
    RejectedExecutionException callerRunsNotSupported();

    @Message(id = 19, value = "No executor queue space remaining")
    RejectedExecutionException noQueueSpaceRemaining();

    // validation

//    @Message(id = 100, value = "Keep-alive may only be set to 0 for this executor type")

//    @Message(id = 101, value = "Cannot reduce maximum threads below current thread number of running threads")

//    @Message(id = 102, value = "Empty array parameter is not empty")

    @Message(id = 103, value = "The current thread does not support interrupt handlers")
    IllegalStateException noInterruptHandlers();

//    @Message(id = 104, value = "Executor is not shut down")

//    @Message(id = 105, value = "Concurrent modification of collection detected")

//    @Message(id = 106, value = "No such element (iteration past end)")

//    @Message(id = 107, value = "Unknown throwable received")

    @Message(id = 108, value = "Interrupt handler %s threw an exception")
    @LogMessage(level = Logger.Level.ERROR)
    void interruptHandlerThrew(@Cause Throwable cause, InterruptHandler interruptHandler);

    @Message(id = 109, value = "Keep-alive time must be positive but was %s")
    IllegalArgumentException nonPositiveKeepAlive(Duration actual);

    // security

    @Message(id = 200, value = "%s() not allowed on container-managed executor")
    SecurityException notAllowedContainerManaged(String methodName);
}
