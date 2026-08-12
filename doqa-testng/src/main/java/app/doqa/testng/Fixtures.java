package app.doqa.testng;

import app.doqa.client.Outcome;
import app.doqa.core.ClassFixtures;
import app.doqa.core.DoqaContexts;
import app.doqa.core.Outcomes;
import app.doqa.core.RuntimeContext;
import app.doqa.core.StepNode;
import app.doqa.core.Steps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

/**
 * TestNG configuration methods projected onto the DoQA model. Every fixture becomes a timed
 * {@link StepNode} whose place depends on the kind of fixture:
 * <ul>
 *   <li>{@code @BeforeMethod} / {@code @BeforeGroups} run before the test's {@code ITestResult}
 *       even exists, so their nodes are buffered on the thread and moved into
 *       {@code setupSteps} by the listener when the test starts;</li>
 *   <li>{@code @AfterMethod} / {@code @AfterGroups} run after the test finished but while its
 *       invocation is still open: the node goes straight into {@code teardownSteps} of the bound
 *       context;</li>
 *   <li>{@code @BeforeClass} / {@code @AfterClass} land in {@link ClassFixtures}, keyed by the
 *       executed class (see {@link #record});</li>
 *   <li>suite- and {@code <test>}-level fixtures have no counterpart in the DoQA model: they only
 *       get a throwaway context so that steps taken inside them are dropped instead of leaking
 *       into whichever test owns the thread next.</li>
 * </ul>
 *
 * <p>While a fixture runs, a context carrying its node as the open step is bound to the thread, so
 * steps, attachments and messages nest under the fixture node instead of being dropped.
 *
 * <p>All state is static and concurrent, and keyed by thread: TestNG runs an invocation together
 * with its fixtures entirely on one thread, in every parallel mode.
 *
 * <p>Internal adapter API.
 */
final class Fixtures {

    enum Kind {
        /** {@code @BeforeMethod} / {@code @BeforeGroups}: setup step of the test that follows. */
        SETUP,
        /** {@code @AfterMethod} / {@code @AfterGroups}: teardown step of the open invocation. */
        TEARDOWN,
        /** {@code @BeforeClass}: class fixture prepended to every test of the class. */
        CLASS_SETUP,
        /** {@code @AfterClass}: class fixture appended to every test of the class. */
        CLASS_TEARDOWN,
        /** Suite / {@code <test>} level: executed under a throwaway context, recorded nowhere. */
        SINK,
        NONE
    }

    /** One running fixture plus what it takes to unwind the thread it was bound on. */
    private static final class Pending {
        final Kind kind;
        final StepNode node;              // null for SINK
        final RuntimeContext previous;    // thread-local to restore; null when the node was bound
        final String invocationKey;       // TEARDOWN: the invocation the node belongs to
        final String classKey;            // CLASS_*: the executed class
        volatile String owner;            // SETUP: the test method this fixture runs for

        Pending(Kind kind, StepNode node, RuntimeContext previous, String invocationKey,
                String classKey) {
            this.kind = kind;
            this.node = node;
            this.previous = previous;
            this.invocationKey = invocationKey;
            this.classKey = classKey;
        }
    }

    /** A closed setup node plus the test method it was run for ({@code null} = the next one). */
    private static final class Buffered {
        final String owner;
        final StepNode node;

        Buffered(String owner, StepNode node) {
            this.owner = owner;
            this.node = node;
        }
    }

    private static final ConcurrentMap<Thread, Pending> PENDING = new ConcurrentHashMap<>();
    /** Closed setup nodes waiting for the {@code onTestStart} of the test they belong to. */
    private static final ConcurrentMap<Thread, List<Buffered>> SETUP_BUFFER =
            new ConcurrentHashMap<>();
    /** Class fixtures already recorded, as {@code class#method} - one recording per run. */
    private static final Set<String> RECORDED = ConcurrentHashMap.newKeySet();

    private Fixtures() {
    }

    static Kind kindOf(ITestNGMethod tm) {
        if (tm == null) {
            return Kind.NONE;
        }
        if (tm.isBeforeMethodConfiguration() || tm.isBeforeGroupsConfiguration()) {
            return Kind.SETUP;
        }
        if (tm.isAfterMethodConfiguration() || tm.isAfterGroupsConfiguration()) {
            return Kind.TEARDOWN;
        }
        if (tm.isBeforeClassConfiguration()) {
            return Kind.CLASS_SETUP;
        }
        if (tm.isAfterClassConfiguration()) {
            return Kind.CLASS_TEARDOWN;
        }
        if (tm.isBeforeSuiteConfiguration() || tm.isAfterSuiteConfiguration()
                || tm.isBeforeTestConfiguration() || tm.isAfterTestConfiguration()) {
            return Kind.SINK;
        }
        return Kind.NONE;
    }

    /**
     * Opens the node of a starting fixture and binds it to the current thread.
     *
     * @param invocationKey for {@link Kind#TEARDOWN}, the invocation whose teardown this is; a
     *                      fixture with no open invocation to attach to becomes a
     *                      {@link Kind#SINK} instead of writing into a foreign test
     */
    static void started(Kind kind, ITestResult tr, String invocationKey) {
        if (kind == null || kind == Kind.NONE) {
            return;
        }
        Thread thread = Thread.currentThread();
        // defensive: a fixture whose completion event never arrived would leave its context bound
        unwind(PENDING.remove(thread));
        String title = titleOf(tr);
        if (kind == Kind.TEARDOWN) {
            RuntimeContext ctx = invocationKey == null ? null : DoqaContexts.bind(invocationKey);
            if (ctx == null) {
                pushSink(thread);
                return;
            }
            ctx.phase = RuntimeContext.Phase.TEARDOWN;
            // the node is opened on the test's own context, so nested steps and attachments taken
            // inside the teardown land under it
            StepNode teardown = Steps.push(title);
            if (teardown == null) {
                pushSink(thread);
                return;
            }
            PENDING.put(thread, new Pending(kind, teardown, null, invocationKey, null));
            return;
        }
        if (kind == Kind.SINK) {
            pushSink(thread);
            return;
        }
        StepNode node = new StepNode(title);
        RuntimeContext fixtureCtx = new RuntimeContext(null);
        fixtureCtx.stepStack.push(node);
        RuntimeContext previous = DoqaContexts.push(fixtureCtx);
        PENDING.put(thread, new Pending(kind, node, previous, null, classKeyOf(tr)));
    }

    /** Closes the fixture running on this thread and files its node where it belongs. */
    static void finished(ITestResult tr) {
        Thread thread = Thread.currentThread();
        Pending pending = PENDING.remove(thread);
        if (pending == null) {
            return;
        }
        String outcome = outcomeOf(tr);
        String message = Outcomes.messageOf(tr == null ? null : tr.getThrowable());
        if (pending.kind == Kind.TEARDOWN) {
            // re-bind before popping: Steps.pop writes to the bound context, not to the thread's last one
            DoqaContexts.bind(pending.invocationKey);
            Steps.pop(outcome, message);
            applyTiming(pending.node, tr);
            return;
        }
        DoqaContexts.restore(pending.previous);
        if (pending.node == null) {
            return;
        }
        pending.node.outcome = outcome;
        if (message != null) {
            // never lose the cause: appended after any note the fixture itself added
            pending.node.message = pending.node.message == null
                    ? message
                    : pending.node.message + "\n" + message;
        }
        applyTiming(pending.node, tr);
        if (pending.kind == Kind.SETUP) {
            buffer(thread, pending.owner, pending.node);
            return;
        }
        record(pending);
    }

    /** Moves the setup nodes buffered on this thread into a starting test - the first moment they fit. */
    static void transferSetupSteps(RuntimeContext ctx, ITestNGMethod testMethod) {
        if (ctx == null) {
            return;
        }
        for (Buffered step : takeBufferedSetup(testMethod)) {
            ctx.setupSteps.add(step.node);
        }
    }

    /**
     * Drops the setup nodes buffered for a test that never started (a broken fixture killed it):
     * left buffered, they would attach to the next test of this thread.
     */
    static void dropBufferedSetup(ITestNGMethod testMethod) {
        takeBufferedSetup(testMethod);
    }

    /**
     * Removes and returns the setup nodes buffered on this thread for {@code testMethod}. A group
     * fixture runs once for a whole group, so under a parallel scheduler the next test to start on
     * this thread need not be the one it prepared: a node with another owner stays buffered.
     */
    private static List<Buffered> takeBufferedSetup(ITestNGMethod testMethod) {
        Thread thread = Thread.currentThread();
        List<Buffered> buffered = SETUP_BUFFER.get(thread);
        if (buffered == null || buffered.isEmpty()) {
            return Collections.emptyList();
        }
        String owner = qualifiedName(testMethod);
        List<Buffered> taken = new ArrayList<>();
        List<Buffered> pending = new ArrayList<>();
        for (Buffered step : buffered) {
            if (step.owner == null || owner == null || step.owner.equals(owner)) {
                taken.add(step);
            } else {
                pending.add(step);
            }
        }
        if (pending.isEmpty()) {
            SETUP_BUFFER.remove(thread);
        } else {
            SETUP_BUFFER.put(thread, pending);
        }
        return taken;
    }

    /**
     * Records the owning test method of the fixture running on this thread; it is known only from the
     * two-argument configuration callback, which arrives after the node was opened.
     */
    static void attachOwner(ITestNGMethod testMethod) {
        Pending pending = PENDING.get(Thread.currentThread());
        if (pending != null && pending.kind == Kind.SETUP && pending.owner == null) {
            pending.owner = qualifiedName(testMethod);
        }
    }

    private static String qualifiedName(ITestNGMethod method) {
        if (method == null || method.getConstructorOrMethod() == null) {
            return null;
        }
        Class<?> declaring = method.getTestClass() != null && method.getTestClass().getRealClass() != null
                ? method.getTestClass().getRealClass()
                : method.getRealClass();
        String owner = declaring == null ? "" : declaring.getName();
        return owner + "#" + method.getMethodName();
    }

    /** Fresh per-run state; paired with {@link ClassFixtures#reset()}, which drops the nodes. */
    static void reset() {
        PENDING.clear();
        SETUP_BUFFER.clear();
        RECORDED.clear();
    }

    // ------------------------------------------------------------------ nodes
    private static void pushSink(Thread thread) {
        PENDING.put(thread, new Pending(Kind.SINK, null, DoqaContexts.push(new RuntimeContext(null)),
                null, null));
    }

    private static void buffer(Thread thread, String owner, StepNode node) {
        // written and read by the owning thread only; the map is concurrent for the run reset
        SETUP_BUFFER.computeIfAbsent(thread, key -> new ArrayList<>()).add(new Buffered(owner, node));
    }

    /**
     * Records a class fixture, once per class per run: the class runs {@code @BeforeClass} again in
     * every {@code <test>} block and for every {@code @Factory} instance.
     */
    private static void record(Pending pending) {
        // the kind is part of the key: a configuration method may take injected arguments, so one
        // class can legally carry a @BeforeClass and an @AfterClass of the very same name
        if (pending.classKey == null
                || !RECORDED.add(pending.classKey + "#" + pending.kind + "#" + pending.node.title)) {
            return;
        }
        if (pending.kind == Kind.CLASS_SETUP) {
            ClassFixtures.recordBefore(pending.classKey, pending.node);
        } else {
            ClassFixtures.recordAfter(pending.classKey, pending.node);
        }
    }

    private static void unwind(Pending pending) {
        if (pending != null && pending.kind != Kind.TEARDOWN) {
            DoqaContexts.restore(pending.previous);
        }
    }

    /** TestNG times configuration methods itself, down to the fixture body. */
    private static void applyTiming(StepNode node, ITestResult tr) {
        if (node == null) {
            return;
        }
        long start = tr == null ? 0L : tr.getStartMillis();
        long end = tr == null ? 0L : tr.getEndMillis();
        if (start > 0L && end >= start) {
            node.durationMs = end - start;
        } else if (node.durationMs == null) {
            node.durationMs = System.currentTimeMillis() - node.startMillis;
        }
    }

    private static String outcomeOf(ITestResult tr) {
        if (tr == null) {
            return Outcome.PASSED.wire();
        }
        switch (tr.getStatus()) {
            case ITestResult.SUCCESS:
                return Outcome.PASSED.wire();
            case ITestResult.SKIP:
                return Outcome.SKIPPED.wire();
            default:
                return Outcomes.failureOutcome(tr.getThrowable());
        }
    }

    private static String titleOf(ITestResult tr) {
        ITestNGMethod method = tr == null ? null : tr.getMethod();
        String name = method == null ? null : method.getMethodName();
        return name == null || name.isEmpty() ? "fixture" : name;
    }

    /**
     * The EXECUTED class, not the declaring one: a {@code @BeforeClass} inherited from a base class
     * belongs to the subclass whose tests it prepares.
     */
    private static String classKeyOf(ITestResult tr) {
        if (tr == null) {
            return null;
        }
        Class<?> executing = tr.getTestClass() == null ? null : tr.getTestClass().getRealClass();
        if (executing == null && tr.getMethod() != null) {
            executing = tr.getMethod().getRealClass();
        }
        return executing == null ? null : executing.getName();
    }
}
