package org.jboss.threads.virtual;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import jdk.internal.vm.ThreadContainer;

/**
 * Access methods for virtual thread internals.
 */
final class Access {
    private static final MethodHandle currentCarrierThread;
    private static final MethodHandle virtualThreadFactory;
    private static final MethodHandle threadStartWithContainer;
    private static final MethodHandle schedulerGetter;
    private static final MethodHandle continuationGetter;

    static {
        MethodHandle ct;
        MethodHandle vtf;
        MethodHandle tswc;
        MethodHandle sg;
        MethodHandle cg;
        try {
            MethodHandles.Lookup thr = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup());
            ct = thr.findStatic(Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
            Class<?> vtbClass = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder", false, null);
            try {
                vtf = thr.findConstructor(vtbClass, MethodType.methodType(void.class, ScheduledExecutorService.class));
            } catch (NoSuchMethodException | NoSuchMethodError ignored) {
                // unpatched JDK
                vtf = thr.findConstructor(vtbClass, MethodType.methodType(void.class, Executor.class));
            }
            // create efficient transformer
            vtf = vtf.asType(MethodType.methodType(Thread.Builder.OfVirtual.class, ThreadScheduler.class));
            // todo: maybe instead, we can directly call `java.lang.ThreadBuilders.newVirtualThread`
            //void start(jdk.internal.vm.ThreadContainer container)
            tswc = thr.findVirtual(Thread.class, "start", MethodType.methodType(void.class, ThreadContainer.class));
            Class<?> vtc = thr.findClass("java.lang.VirtualThread");
            MethodHandles.Lookup vthr = MethodHandles.privateLookupIn(vtc, MethodHandles.lookup());
            try {
                sg = vthr.findGetter(vtc, "scheduler", ScheduledExecutorService.class);
            } catch (NoSuchFieldException | NoSuchFieldError ignored) {
                // unpatched JDK
                sg = vthr.findGetter(vtc, "scheduler", Executor.class);
            }
            sg = sg.asType(MethodType.methodType(Executor.class, Thread.class));
            cg = vthr.findGetter(vtc, "runContinuation", Runnable.class).asType(MethodType.methodType(Runnable.class, Thread.class));
        } catch (Throwable e) {
            // no good
            throw new InternalError("Cannot initialize virtual threads", e);
        }
        currentCarrierThread = ct;
        virtualThreadFactory = vtf;
        threadStartWithContainer = tswc;
        schedulerGetter = sg;
        continuationGetter = cg;
    }

    static Thread currentCarrier() {
        try {
            return (Thread) currentCarrierThread.invokeExact();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    static Thread.Builder.OfVirtual threadBuilder(ThreadScheduler threadScheduler) {
        try {
            return (Thread.Builder.OfVirtual) virtualThreadFactory.invokeExact(threadScheduler);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    static void startThread(Thread thread, ThreadContainer threadContainer) {
        try {
            threadStartWithContainer.invokeExact(thread, threadContainer);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    static Executor schedulerOf(Thread thread) {
        try {
            return (Executor) schedulerGetter.invokeExact(thread);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    static Runnable continuationOf(Thread thread) {
        try {
            return (Runnable) continuationGetter.invokeExact(thread);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }
}
