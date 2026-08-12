package app.doqa.testng;

import app.doqa.core.DoqaContexts;
import app.doqa.core.RuntimeContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry of the invocations that have started but have not been reported yet - the mechanism the
 * whole TestNG adapter is built around.
 *
 * <p>TestNG sends no per-test event after the method teardown: the final {@code ITestListener}
 * callback ({@code onTestSuccess} and friends) fires BEFORE {@code @AfterMethod} /
 * {@code @AfterGroups} run. Reporting from there would drop every teardown step, so those callbacks
 * only FIX the outcome here, and the result is sent at the next event that proves the teardown is
 * over: the next invocation or the next before-fixture on the same thread, the end of the class,
 * the end of the {@code <test>}, or the end of the run.
 *
 * <p>Invariant: an invocation is sent exactly once, and never while its outcome is unfixed.
 *
 * <p>All state is static and concurrent: the invocations of one run live on many threads while the
 * sweeping events arrive on yet other threads.
 *
 * <p>Internal adapter API.
 */
final class Invocations {

    /** Sends one finished invocation; the listener implements it as build + report. */
    interface Sink {
        void send(RuntimeContext ctx, String outcome, String message, String traces);
    }

    /** One invocation: its context plus the outcome fixed by the final {@code ITestListener} call. */
    private static final class Invocation {
        final String key;
        final RuntimeContext ctx;
        final String classFqcn;
        final String contextName;
        final Thread thread;
        final long sequence;
        final AtomicBoolean sent = new AtomicBoolean();
        volatile String outcome;   // null while the test has not finished - never sent in that state
        volatile String message;
        volatile String traces;

        Invocation(String key, RuntimeContext ctx, String classFqcn, String contextName,
                   Thread thread, long sequence) {
            this.key = key;
            this.ctx = ctx;
            this.classFqcn = classFqcn;
            this.contextName = contextName;
            this.thread = thread;
            this.sequence = sequence;
        }
    }

    private static final Logger LOG = Logger.getLogger(Invocations.class.getName());

    private static final ConcurrentMap<String, Invocation> OPEN = new ConcurrentHashMap<>();
    /** Report order within one sweep: invocations are reported in the order they started. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private Invocations() {
    }

    /** Registers the invocation opened by {@code onTestStart} on the thread it runs on. */
    static void open(String key, RuntimeContext ctx, String classFqcn, String contextName,
                     Thread thread) {
        if (key == null || ctx == null) {
            return;
        }
        Invocation previous = OPEN.put(key, new Invocation(key, ctx, classFqcn, contextName, thread,
                SEQUENCE.incrementAndGet()));
        if (previous != null) {
            // Defensive: only a foreign listener copying our key attribute gets here, and this
            // thread's sweep has already run - the superseded entry can no longer be sent.
            LOG.log(Level.FINE, "DoQA testng: invocation key reused, one result is dropped: " + key);
        }
    }

    /**
     * Fixes the outcome of a finished test; the result is sent later, once its teardown has run.
     * {@code false} when the key belongs to no open invocation - the caller then reports it directly.
     */
    static boolean outcome(String key, String outcome, String message, String traces, long tEnd) {
        Invocation invocation = key == null ? null : OPEN.get(key);
        if (invocation == null) {
            return false;
        }
        // the volatile outcome is the readiness flag of a sweeping thread and publishes everything
        // written before it, so the plain fields of the context go first
        invocation.ctx.tEnd = tEnd;
        invocation.message = message;
        invocation.traces = traces;
        invocation.outcome = outcome;
        return true;
    }

    /** Key of the invocation currently open on {@code thread} - the owner of its teardown fixtures. */
    static String currentKey(Thread thread) {
        Invocation latest = null;
        for (Invocation invocation : OPEN.values()) {
            if (invocation.thread == thread
                    && (latest == null || invocation.sequence > latest.sequence)) {
                latest = invocation;
            }
        }
        return latest == null ? null : latest.key;
    }

    /**
     * Sends the finished invocations of {@code thread}. Every trigger of this sweep (the next
     * {@code onTestStart}, a before-fixture of the next test) happens after the previous
     * invocation's teardown and before the next one is registered, so there is nothing to exclude.
     */
    static void emitThread(Thread thread, Sink sink) {
        List<Invocation> ready = new ArrayList<>();
        for (Invocation invocation : OPEN.values()) {
            if (invocation.thread == thread && invocation.outcome != null) {
                ready.add(invocation);
            }
        }
        send(ready, sink);
    }

    /** Sends the finished invocations of one test class (its last test gets no further event). */
    static void emitClass(String classFqcn, Sink sink) {
        if (classFqcn == null) {
            return;
        }
        List<Invocation> ready = new ArrayList<>();
        for (Invocation invocation : OPEN.values()) {
            if (invocation.outcome != null && classFqcn.equals(invocation.classFqcn)) {
                ready.add(invocation);
            }
        }
        send(ready, sink);
    }

    /**
     * Sends the finished invocations of one {@code <test>} block. Scoped by the block, NOT by its
     * classes: the same class routinely runs in several blocks, and with {@code parallel="tests"} a
     * class-wide sweep would send another block's invocation while its {@code @AfterMethod} still
     * runs - losing exactly the teardown this registry exists to preserve.
     */
    static void emitContext(String contextName, Sink sink) {
        if (contextName == null) {
            return;
        }
        List<Invocation> ready = new ArrayList<>();
        for (Invocation invocation : OPEN.values()) {
            if (invocation.outcome != null && contextName.equals(invocation.contextName)) {
                ready.add(invocation);
            }
        }
        send(ready, sink);
    }

    /** Sends everything finished - the run boundary, where nothing can still be running. */
    static void emitAll(Sink sink) {
        List<Invocation> ready = new ArrayList<>();
        for (Invocation invocation : OPEN.values()) {
            if (invocation.outcome != null) {
                ready.add(invocation);
            }
        }
        send(ready, sink);
    }

    /** Fresh per-run state; drops contexts of invocations that never finished. */
    static void reset() {
        for (Invocation invocation : OPEN.values()) {
            DoqaContexts.remove(invocation.key);
        }
        OPEN.clear();
    }

    // ------------------------------------------------------------------ reporting
    private static void send(List<Invocation> batch, Sink sink) {
        if (batch.isEmpty() || sink == null) {
            return;
        }
        Collections.sort(batch, Comparator.comparingLong(invocation -> invocation.sequence));
        for (Invocation invocation : batch) {
            OPEN.remove(invocation.key, invocation);
            if (!invocation.sent.compareAndSet(false, true)) {
                continue;
            }
            // the context leaves the registry only now: @AfterMethod still needed to bind it
            DoqaContexts.remove(invocation.key);
            try {
                sink.send(invocation.ctx, invocation.outcome, invocation.message,
                        invocation.traces);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "DoQA testng: report failed for " + invocation.key, e);
            }
        }
    }
}
