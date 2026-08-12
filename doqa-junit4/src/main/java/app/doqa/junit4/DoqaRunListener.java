package app.doqa.junit4;

import app.doqa.client.Outcome;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.ClassFixtures;
import app.doqa.core.DoqaContexts;
import app.doqa.core.DoqaSession;
import app.doqa.core.Outcomes;
import app.doqa.core.PlanSelection;
import app.doqa.core.ResultBuilder;
import app.doqa.core.RuntimeContext;
import app.doqa.core.TestRef;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

/**
 * Thick JUnit 4 {@link RunListener} for DoQA. Initializes the client + run (mode) on its first
 * event; on each finished test it builds an {@code AutotestDef} + {@code AutotestResult} from the
 * collected {@link RuntimeContext} and attribution, then hands it to the session (batched, streamed
 * per class in realtime, or written to files). Reporting failures are logged as warnings and never
 * rethrown into the build.
 *
 * <p>JUnit 4 has no service-loader registration, so the host attaches the listener: surefire's
 * {@code listener} property, or {@link DoqaRunner} through {@link #ensureRegistered(RunNotifier)}.
 *
 * <p>What JUnit 4 does not report on its own is reconstructed here:
 * <ul>
 *   <li>class fixtures, timed between the suite and test events (see {@link ClassFixtureTracker});</li>
 *   <li>a class-level failure ({@code @BeforeClass} or a {@code @ClassRule} threw) produces no test
 *       events at all, and {@code @Ignore} on a class fires a single childless event: both are
 *       expanded, so every test of the class is still reported;</li>
 *   <li>one test can report several failures (a failing body plus a failing {@code @After}), so
 *       throwables are collected per test and merged when it finishes;</li>
 *   <li>skips are recognized by the callback, never by the exception type:
 *       {@code AssumptionViolatedException} is a {@code RuntimeException} and would otherwise be
 *       reported as broken.</li>
 * </ul>
 *
 * <p>Marked {@link RunListener.ThreadSafe} because all state is concurrent: without the marker JUnit
 * wraps the listener in a {@code SynchronizedRunListener}, which serializes the callbacks of the
 * whole parallel run. Every callback catches {@link Throwable} - an exception escaping into JUnit
 * becomes a bogus "Test mechanism" failure and reddens the build. The minimum supported version is
 * JUnit 4.13, and nothing depends on the suite events, which 4.12 never sends - they only make
 * fixtures and per-class streaming more accurate.
 */
@RunListener.ThreadSafe
public class DoqaRunListener extends RunListener {

    static {
        AdapterRuntime.configure("junit4", "junit4");
    }

    private static final Logger LOG = Logger.getLogger(DoqaRunListener.class.getName());

    private volatile DoqaSession session;
    private volatile boolean initialized;

    /** Throwables of a test that has not finished yet - JUnit reports one callback per throwable. */
    private final ConcurrentMap<String, List<Throwable>> failures = new ConcurrentHashMap<>();
    /** Tests whose assumption failed; reported as skipped once they finish. */
    private final ConcurrentMap<String, Optional<Throwable>> assumptions = new ConcurrentHashMap<>();
    /** Class of the test events arriving now - the class-boundary fallback for missing suite events. */
    private volatile String currentClass;

    /**
     * Attaches a listener to {@code notifier} unless the run already has an externally registered
     * one. Called by {@link DoqaRunner}, the only entry point available under Gradle or an IDE;
     * under surefire the {@code listener} property has already registered one, and a second
     * listener would repeat work the first one is doing.
     */
    static void ensureRegistered(RunNotifier notifier) {
        if (AdapterState.isListenerActive() || !AdapterState.claimNotifier(notifier)) {
            return;
        }
        notifier.addListener(new DoqaRunListener());
    }

    @Override
    public void testRunStarted(Description description) {
        try {
            // Fresh per-run state, and only on the first run boundary (see AdapterState.beginRun):
            // a surefire rerun re-runs @BeforeClass within the same run, and a long-lived JVM
            // (Gradle daemon, IDE) may reload the test classes.
            if (AdapterState.beginRun()) {
                AdapterState.reset();
                ClassFixtures.reset();
                ClassFixtureTracker.reset();
                Reflections.reset();
                PlanSelection.reset();
                failures.clear();
                assumptions.clear();
                currentClass = null;
            }
            // This event fires once per fork, not once per run - it starts no run of its own (the
            // session does that, idempotently), it only marks a state boundary.
            session();
            warnOnEmptySelection(description);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: run start failed", t);
        }
    }

    @Override
    public void testSuiteStarted(Description description) {
        try {
            if (!active()) {
                return;
            }
            Class<?> testClass = description.getTestClass();
            if (testClass == null) {
                // a Parameterized parameter set reports its invocation name as the class name, and
                // surefire's parallel provider inserts synthetic grouping levels
                return;
            }
            String fqcn = testClass.getName();
            if (AdapterState.isRunnerManaged(fqcn)) {
                // DoqaRunner times every fixture method individually - a coarse node would duplicate it
                return;
            }
            if (Reflections.hasAnnotatedMethod(testClass, BeforeClass.class)
                    && !allChildrenIgnored(description)) {
                ClassFixtureTracker.openBefore(fqcn, testClass);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: suite start failed for " + nameOf(description), t);
        }
    }

    @Override
    public void testStarted(Description description) {
        try {
            if (!active()) {
                return;
            }
            TestRef ref = TestRefs.fromDescription(description);
            ClassFixtureTracker.closeBefore(ref.fqcn);
            crossClassBoundary(ref.fqcn);
            String key = TestRefs.key(description);
            // a rerun of a failed test happens inside the same run and is a result of its own
            AdapterState.clearReported(key);
            RuntimeContext ctx = DoqaContexts.open(key);
            ctx.testRef = ref;
            ctx.tStart = System.currentTimeMillis();
            // @Category is the JUnit 4 counterpart of a @Tag, so it is reported as a tag
            ctx.tags.addAll(TestRefs.categories(ref.testClass, ref.testMethod));
            if (!AdapterState.isRunnerManaged(ref.fqcn)) {
                // the invocation name is all a plain listener sees; DoqaRunner has the real
                // argument values and fills them in itself
                ctx.invocationParameters.addAll(
                        TestRefs.invocationParameters(description.getMethodName()));
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: test start failed for " + nameOf(description), t);
        }
    }

    @Override
    public void testFailure(Failure failure) {
        try {
            if (!active() || failure == null || failure.getDescription() == null) {
                return;
            }
            Description description = failure.getDescription();
            Throwable error = failure.getException();
            if (description.getMethodName() != null) {
                // A test can fail more than once (body plus @After, or a MultipleFailureException
                // unwrapped into one callback per cause): collect, decide at testFinished.
                failures.computeIfAbsent(TestRefs.key(description),
                        key -> new CopyOnWriteArrayList<>()).add(error);
                return;
            }
            Class<?> testClass = description.getTestClass();
            if (testClass == null) {
                // Description.TEST_MECHANISM: JUnit turns an exception thrown by any listener into
                // this pseudo-failure - there is nothing of ours to report
                LOG.log(Level.WARNING, "DoQA junit4: JUnit reported a test-mechanism failure", error);
                return;
            }
            reportClassLevelOutcome(description, testClass, error, Outcomes.failureOutcome(error), true);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: failure handling failed", t);
        }
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        try {
            if (!active() || failure == null || failure.getDescription() == null) {
                return;
            }
            Description description = failure.getDescription();
            Throwable error = failure.getException();
            if (description.getMethodName() != null) {
                assumptions.put(TestRefs.key(description), Optional.ofNullable(error));
                return;
            }
            Class<?> testClass = description.getTestClass();
            if (testClass == null) {
                LOG.log(Level.WARNING, "DoQA junit4: JUnit reported a test-mechanism failure", error);
                return;
            }
            // an Assume inside @BeforeClass: the whole class is not applicable
            reportClassLevelOutcome(description, testClass, error, Outcome.SKIPPED.wire(), false);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: assumption handling failed", t);
        }
    }

    @Override
    public void testIgnored(Description description) {
        try {
            if (!active()) {
                return;
            }
            String reason = ignoreReason(description);
            if (description.getMethodName() != null) {
                reportSynthetic(TestRefs.key(description), TestRefs.fromDescription(description),
                        Outcome.SKIPPED.wire(), reason, null);
                return;
            }
            // @Ignore on the class fires ONE event, without children (its isTest() is even true):
            // reflection is the only way to name the tests that were skipped.
            Class<?> testClass = description.getTestClass();
            if (testClass == null) {
                return;
            }
            for (Method method : declaredTests(testClass)) {
                reportSynthetic(syntheticKey(testClass, method),
                        TestRefs.fromMethod(testClass, method), Outcome.SKIPPED.wire(), reason, null);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: skip report failed for " + nameOf(description), t);
        }
    }

    @Override
    public void testFinished(Description description) {
        try {
            if (!active()) {
                return;
            }
            String key = TestRefs.key(description);
            RuntimeContext ctx = DoqaContexts.remove(key);
            if (ctx == null) {
                // defensive: a runner that finishes a test it never started, or an event pair split
                // between two listener instances
                ctx = new RuntimeContext(key);
                ctx.testRef = TestRefs.fromDescription(description);
                ctx.tags.addAll(TestRefs.categories(ctx.testRef.testClass, ctx.testRef.testMethod));
            }
            ctx.tEnd = System.currentTimeMillis();
            Optional<Throwable> assumed = assumptions.remove(key);
            List<Throwable> errors = failures.remove(key);
            if (assumed != null) {
                Throwable error = assumed.orElse(null);
                emit(ctx, Outcome.SKIPPED.wire(), Outcomes.messageOf(error),
                        Outcomes.stackTrace(error));
            } else if (errors == null || errors.isEmpty()) {
                emit(ctx, Outcome.PASSED.wire(), null, null);
            } else {
                emit(ctx, outcomeOf(errors), messagesOf(errors), tracesOf(errors));
            }
            openAfterFixture(ctx.testRef);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: report failed for " + nameOf(description), t);
        }
    }

    @Override
    public void testSuiteFinished(Description description) {
        try {
            if (!active()) {
                return;
            }
            Class<?> testClass = description.getTestClass();
            if (testClass == null) {
                return;
            }
            String fqcn = testClass.getName();
            if (!AdapterState.isRunnerManaged(fqcn)) {
                // closeBefore also covers a class whose tests never started (all of them filtered
                // out by the host), whose @BeforeClass would otherwise stay open
                ClassFixtureTracker.closeBefore(fqcn);
                ClassFixtureTracker.closeAfter(fqcn);
            }
            if (fqcn.equals(DoqaSession.topLevelClass(fqcn))) {
                // realtime: a finished TOP-LEVEL class means its @AfterClass ran - stream the
                // class's results with complete fixtures (nested classes go with the outer one)
                flushClass(fqcn);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: suite finish failed for " + nameOf(description), t);
        }
    }

    @Override
    public void testRunFinished(Result result) {
        // The Result is deliberately not read: its 4.13-only getters would kill a 4.12 fork, and
        // its run time is still zero at this point anyway.
        try {
            DoqaSession local = session();
            if (local == null || !local.enabled) {
                return;
            }
            local.flush();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: batch flush failed", t);
        } finally {
            AdapterState.endRun();
        }
    }

    // ------------------------------------------------------------------ reporting
    /**
     * A failure or assumption failure carrying a class description means the class-level fixtures
     * blew up: JUnit sends no test events for such a class at all, so the outcome is marked on the
     * {@code @BeforeClass} node and synthesized for every test of the class that has not reported.
     */
    private void reportClassLevelOutcome(Description description, Class<?> testClass, Throwable error,
                                         String outcome, boolean failed) {
        String fqcn = testClass.getName();
        // A class-level failure arriving once the tests have run is a failing @AfterClass: the tests
        // already reported their own outcomes, and only the teardown node carries the error.
        if (failed && ClassFixtureTracker.failAfter(fqcn, error)) {
            return;
        }
        if (failed) {
            ClassFixtureTracker.failBefore(fqcn, error);
        } else {
            ClassFixtureTracker.skipBefore(fqcn, error);
        }
        // record the fixture before the synthetic results: the file sink attaches @BeforeClass at
        // report time
        ClassFixtureTracker.closeBefore(fqcn);
        String message = Outcomes.messageOf(error);
        String traces = Outcomes.stackTrace(error);
        List<Description> tests = new ArrayList<>();
        collectTests(description, tests);
        if (!tests.isEmpty()) {
            for (Description test : tests) {
                reportSynthetic(TestRefs.key(test), TestRefs.fromDescription(test),
                        outcome, message, traces);
            }
            return;
        }
        // no children (a childless class description, e.g. an empty runner): fall back to reflection
        for (Method method : declaredTests(testClass)) {
            reportSynthetic(syntheticKey(testClass, method), TestRefs.fromMethod(testClass, method),
                    outcome, message, traces);
        }
    }

    /** Report a test that produced no events of its own (skipped, or killed by a class fixture). */
    private void reportSynthetic(String key, TestRef ref, String outcome, String message,
                                 String traces) {
        if (key == null || ref == null) {
            return;
        }
        RuntimeContext ctx = new RuntimeContext(key);
        ctx.testRef = ref;
        ctx.tEnd = ctx.tStart;
        ctx.tags.addAll(TestRefs.categories(ref.testClass, ref.testMethod));
        emit(ctx, outcome, message, traces);
    }

    private void emit(RuntimeContext ctx, String outcome, String message, String traces) {
        DoqaSession local = session;
        if (local == null || !local.enabled || !AdapterState.markReported(ctx.uniqueId)) {
            return;
        }
        try {
            ResultBuilder.Built built = ResultBuilder.build(ctx, outcome, message, traces,
                    local.uploader(), local::allowsId, local.config);
            local.report(built);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA junit4: report failed for " + ctx.uniqueId, e);
        }
    }

    private void flushClass(String fqcn) {
        try {
            session.flushClass(fqcn);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA junit4: class flush failed for " + fqcn, e);
        }
    }

    // ------------------------------------------------------------------ session
    /**
     * The session, initialized on the first event this listener sees. Initialization cannot wait for
     * {@code testRunStarted}: under Gradle or an IDE the listener is attached by the runner, which
     * happens after that event has already been fired (or without it ever being fired). A failed
     * init leaves the session null and the adapter silent.
     */
    private DoqaSession session() {
        if (initialized) {
            return session;
        }
        synchronized (this) {
            if (!initialized) {
                try {
                    session = DoqaSession.getOrInit();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "DoQA junit4: init failed, disabling", e);
                }
                initialized = true;
                // tells DoqaRunner that reporting is covered and it needs no listener of its own
                AdapterState.listenerActive();
            }
        }
        return session;
    }

    private boolean active() {
        DoqaSession local = session();
        return local != null && local.enabled;
    }

    /**
     * A selective run that matched nothing executes zero tests and still looks green, so say it out
     * loud: the run's external ids and the tests the host discovered have drifted apart - typically
     * a renamed method or a moved class.
     */
    private void warnOnEmptySelection(Description description) {
        DoqaSession local = session;
        if (local == null || !local.enabled || local.runContext == null
                || local.runContext.selectedExternalIds() == null
                || local.runContext.selectedExternalIds().isEmpty()) {
            return;
        }
        if (description != null && description.testCount() == 0) {
            LOG.warning("DoQA: the run selects " + local.runContext.selectedExternalIds().size()
                    + " autotest(s), but none of them matched the discovered tests - nothing will"
                    + " run. The selected external ids are " + local.runContext.selectedExternalIds()
                    + "; re-report this suite to DoQA so the catalog picks up the current ids.");
        }
    }

    // ------------------------------------------------------------------ helpers
    /**
     * Streams the previous class when the reporting class changes. A safety net for JUnit 4.12 and
     * for providers that send no suite events; {@link DoqaSession#flushClass} is idempotent, so a
     * class already streamed by its suite event simply has nothing left.
     */
    private void crossClassBoundary(String fqcn) {
        String previous = currentClass;
        if (fqcn == null || fqcn.equals(previous)) {
            return;
        }
        currentClass = fqcn;
        // compared at top-level granularity: nested classes are streamed with their outer class,
        // whose @AfterClass runs only after the last of them
        String previousTop = DoqaSession.topLevelClass(previous);
        if (previousTop != null && !previousTop.equals(DoqaSession.topLevelClass(fqcn))) {
            flushClass(previousTop);
        }
    }

    /**
     * Opens (or re-opens) the class's {@code @AfterClass} node. There is no "last test" event in
     * JUnit 4, so every finished test starts a new node and only the last one survives to be closed
     * by {@code testSuiteFinished} - which is exactly the one that spans {@code @AfterClass}.
     */
    private void openAfterFixture(TestRef ref) {
        if (ref == null || ref.testClass == null || AdapterState.isRunnerManaged(ref.fqcn)) {
            return;
        }
        if (Reflections.hasAnnotatedMethod(ref.testClass, AfterClass.class)) {
            ClassFixtureTracker.openAfter(ref.fqcn, ref.testClass);
        }
    }

    /**
     * Mirrors {@code ParentRunner.areAllChildrenIgnored}: a class whose every test is
     * {@code @Ignore}d never runs its {@code @BeforeClass}, so timing one would attach a fixture
     * that did not happen to the skipped results.
     */
    private static boolean allChildrenIgnored(Description description) {
        List<Description> children = description.getChildren();
        if (children.isEmpty()) {
            return false;
        }
        for (Description child : children) {
            if (child.getAnnotation(Ignore.class) == null) {
                return false;
            }
        }
        return true;
    }

    /** Test descriptions below {@code description}, at any depth (Parameterized nests one level). */
    private static void collectTests(Description description, List<Description> out) {
        for (Description child : description.getChildren()) {
            if (child.getMethodName() != null) {
                out.add(child);
            } else {
                collectTests(child, out);
            }
        }
    }

    private static List<Method> declaredTests(Class<?> testClass) {
        return Reflections.annotatedMethods(testClass, Test.class);
    }

    /** Context key of a reflected test: the very key it would have reported under had it run. */
    private static String syntheticKey(Class<?> testClass, Method method) {
        return TestRefs.key(Description.createTestDescription(testClass, method.getName()));
    }

    /** Broken as soon as one throwable is not an assertion - the infrastructure signal wins. */
    private static String outcomeOf(List<Throwable> errors) {
        for (Throwable error : errors) {
            if (!Outcomes.isAssertion(error)) {
                return Outcome.BROKEN.wire();
            }
        }
        return Outcome.FAILED.wire();
    }

    private static String messagesOf(List<Throwable> errors) {
        StringBuilder joined = new StringBuilder();
        for (Throwable error : errors) {
            String message = Outcomes.messageOf(error);
            if (message == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(message);
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private static String tracesOf(List<Throwable> errors) {
        StringBuilder joined = new StringBuilder();
        for (Throwable error : errors) {
            String trace = Outcomes.stackTrace(error);
            if (trace == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append("\n\n");
            }
            joined.append(trace);
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /** The {@code @Ignore} reason, or null when the annotation carries no text. */
    private static String ignoreReason(Description description) {
        Ignore ignore = description.getAnnotation(Ignore.class);
        if (ignore == null || ignore.value() == null || ignore.value().trim().isEmpty()) {
            return null;
        }
        return ignore.value();
    }

    private static String nameOf(Description description) {
        return description == null ? "an unnamed description" : description.getDisplayName();
    }
}
