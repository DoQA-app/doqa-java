package app.doqa.testng;

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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.testng.IClassListener;
import org.testng.IConfigurationListener;
import org.testng.IExecutionListener;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.SkipException;

/**
 * Thick TestNG listener for DoQA. Initializes the client + run (mode) on its first event; for each
 * finished test it builds an {@code AutotestDef} + {@code AutotestResult} from the collected
 * {@link RuntimeContext} and attribution, then hands it to the session (batched, streamed per class
 * in realtime, or written to files). Reporting failures are logged as warnings and never rethrown
 * into the build - an exception escaping a TestNG listener is turned into a configuration failure
 * and reddens the host's suite.
 *
 * <p>One class covers five listener roles: TestNG registers a listener for every interface it
 * implements, so one line in {@code META-INF/services/org.testng.ITestNGListener} enables all of them.
 *
 * <p>Results are not sent from the final {@code ITestListener} callback, which fires BEFORE
 * {@code @AfterMethod} - {@link Invocations} owns the deferred send. Everything else TestNG reports
 * generously: even a test killed by a broken fixture gets its own start and skip events.
 *
 * <p>Known gaps:
 * <ul>
 *   <li>{@code @Test(enabled = false)} produces no events at all; those tests are read off the
 *       {@code <test>}'s excluded methods and reported as skipped;</li>
 *   <li>suite- and {@code <test>}-level fixtures have no counterpart in the DoQA model and are not
 *       attached to results (they still get a throwaway context, so steps taken inside them cannot
 *       leak into a foreign test).</li>
 * </ul>
 */
public class DoqaTestNgListener implements ITestListener, ISuiteListener, IClassListener,
        IConfigurationListener, IExecutionListener {

    static {
        AdapterRuntime.configure("testng", "testng");
        AdapterRuntime.configureSkipSignal(SkipException::new);
    }

    private static final Logger LOG = Logger.getLogger(DoqaTestNgListener.class.getName());

    /** Marker attributes on a configuration result. */
    private static final String STARTED_MARKER = "doqa.fixtureStarted";
    private static final String FINISHED_MARKER = "doqa.fixtureFinished";
    /** Context key of a test that never ran, so it cannot collide with an invocation key. */
    private static final String DISABLED_PREFIX = "disabled:";

    /** Run state is static: the host may register this listener at more than one level. */
    private static final AtomicBoolean RUN_ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean SELECTION_WARNED = new AtomicBoolean();
    /** Context keys already reported this run - guards double delivery and repeated {@code <test>}s. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private volatile DoqaSession session;
    private volatile boolean initialized;
    private final Invocations.Sink sink = this::emit;

    // ------------------------------------------------------------------ run boundaries
    @Override
    public void onExecutionStart() {
        try {
            beginRun();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: run start failed", t);
        }
    }

    @Override
    public void onStart(ISuite suite) {
        try {
            // Also a run boundary: the registration path decides which of the two events arrives
            // first, and neither is guaranteed to be there at all.
            beginRun();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: suite start failed for " + nameOf(suite), t);
        }
    }

    @Override
    public void onFinish(ISuite suite) {
        try {
            if (!active()) {
                return;
            }
            Invocations.emitAll(sink);
            warnOnEmptySelection();
            flush();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: suite finish failed for " + nameOf(suite), t);
        }
    }

    @Override
    public void onExecutionFinish() {
        // Both this and the suite finish flush: a suite may run without an ISuite wrapper, and this
        // event is missing under @Listeners; a second flush finds an empty buffer.
        try {
            if (active()) {
                Invocations.emitAll(sink);
                flush();
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: run finish failed", t);
        } finally {
            RUN_ACTIVE.set(false);
        }
    }

    // ------------------------------------------------------------------ <test> boundaries
    @Override
    public void onStart(ITestContext context) {
        try {
            if (!active() || context == null) {
                return;
            }
            reportDisabledTests(context);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: context start failed for " + nameOf(context), t);
        }
    }

    @Override
    public void onFinish(ITestContext context) {
        try {
            if (!active() || context == null) {
                return;
            }
            // the last invocation of this block gets no further per-test event of its own
            Invocations.emitContext(context.getName(), sink);
            Set<String> classes = classesOf(context);
            // realtime: every @AfterClass of this <test> has run by now - IClassListener's
            // onAfterClass arrives BEFORE @AfterClass and would stream incomplete fixtures
            for (String fqcn : classes) {
                flushClass(fqcn);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: context finish failed for " + nameOf(context), t);
        }
    }

    // ------------------------------------------------------------------ class boundary
    @Override
    public void onAfterClass(ITestClass testClass) {
        try {
            if (!active() || testClass == null || testClass.getRealClass() == null) {
                return;
            }
            // Sweep the class's last invocation, which no thread event will come back for. NOT a
            // streaming point: @AfterClass runs after this callback.
            Invocations.emitClass(testClass.getRealClass().getName(), sink);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: class finish failed", t);
        }
    }

    // ------------------------------------------------------------------ one invocation
    @Override
    public void onTestStart(ITestResult result) {
        try {
            if (!active() || result == null) {
                return;
            }
            // this thread's previous invocation is complete, teardown included: send it now
            Invocations.emitThread(Thread.currentThread(), sink);
            String key = TestRefs.key(result);
            RuntimeContext ctx = DoqaContexts.open(key);
            ctx.testRef = TestRefs.fromResult(result);
            ctx.tStart = result.getStartMillis() > 0L
                    ? result.getStartMillis()
                    : System.currentTimeMillis();
            // this event runs on the invocation's own thread, so the phase set here holds for its body
            ctx.phase = RuntimeContext.Phase.CALL;
            ctx.tags.addAll(TestRefs.groups(result.getMethod()));
            ctx.invocationParameters.addAll(TestRefs.invocationParameters(result));
            String testName = result.getTestName();
            if (testName != null && !testName.trim().isEmpty()) {
                // @Test(testName) / ITest#getTestName(): a runtime name override, deliberately kept
                // out of the identity signature so a per-instance name cannot split the history
                ctx.displayName = testName.trim();
            }
            Fixtures.transferSetupSteps(ctx, result.getMethod());
            Invocations.open(key, ctx, ctx.testRef == null ? null : ctx.testRef.fqcn,
                    result.getTestContext() == null ? null : result.getTestContext().getName(),
                    Thread.currentThread());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: test start failed for " + nameOf(result), t);
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        recordOutcome(result);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        recordOutcome(result);
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        recordOutcome(result);
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        recordOutcome(result);
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        // TestNG sends either this or onTestFailure, never both - the override only spells that out.
        recordOutcome(result);
    }

    // ------------------------------------------------------------------ fixtures
    // Both overloads of every configuration callback are implemented: TestNG calls the
    // single-argument one first, and a marker on the result keeps the work at exactly once.
    @Override
    public void beforeConfiguration(ITestResult tr) {
        configurationStarted(tr);
    }

    @Override
    public void beforeConfiguration(ITestResult tr, ITestNGMethod tm) {
        configurationStarted(tr);
        try {
            // This overload is the only one naming the test method a @BeforeMethod / @BeforeGroups
            // runs for, and it arrives after the node was opened by the single-argument one.
            if (tm != null) {
                Fixtures.attachOwner(tm);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: fixture owner not recorded for " + nameOf(tr), t);
        }
    }

    @Override
    public void onConfigurationSuccess(ITestResult tr) {
        configurationFinished(tr);
    }

    @Override
    public void onConfigurationSuccess(ITestResult tr, ITestNGMethod tm) {
        configurationFinished(tr);
    }

    @Override
    public void onConfigurationFailure(ITestResult tr) {
        configurationFinished(tr);
    }

    @Override
    public void onConfigurationFailure(ITestResult tr, ITestNGMethod tm) {
        configurationFinished(tr);
    }

    @Override
    public void onConfigurationSkip(ITestResult tr) {
        configurationFinished(tr);
    }

    @Override
    public void onConfigurationSkip(ITestResult tr, ITestNGMethod tm) {
        configurationFinished(tr);
    }

    private void configurationStarted(ITestResult tr) {
        try {
            if (!active() || tr == null || !claim(tr, STARTED_MARKER)) {
                return;
            }
            ITestNGMethod configMethod = tr.getMethod();
            Fixtures.Kind kind = Fixtures.kindOf(configMethod);
            if (kind == Fixtures.Kind.NONE) {
                return;
            }
            if (runsBeforeTest(configMethod)) {
                // A fixture that precedes a test proves the previous invocation on this thread is
                // done, teardown included - send it before this fixture starts recording steps.
                Invocations.emitThread(Thread.currentThread(), sink);
            }
            String invocationKey = kind == Fixtures.Kind.TEARDOWN
                    ? Invocations.currentKey(Thread.currentThread())
                    : null;
            Fixtures.started(kind, tr, invocationKey);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: fixture start failed for " + nameOf(tr), t);
        }
    }

    private void configurationFinished(ITestResult tr) {
        try {
            if (!active() || tr == null || !claim(tr, FINISHED_MARKER)) {
                return;
            }
            Fixtures.finished(tr);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: fixture finish failed for " + nameOf(tr), t);
        }
    }

    /** True for the fixtures that run before a test can be pending on the current thread. */
    private static boolean runsBeforeTest(ITestNGMethod tm) {
        return tm != null
                && (tm.isBeforeMethodConfiguration() || tm.isBeforeGroupsConfiguration()
                || tm.isBeforeClassConfiguration() || tm.isBeforeTestConfiguration()
                || tm.isBeforeSuiteConfiguration());
    }

    /** First caller wins: both overloads of a configuration callback carry the same result. */
    private static boolean claim(ITestResult tr, String marker) {
        if (tr.getAttribute(marker) != null) {
            return false;
        }
        tr.setAttribute(marker, Boolean.TRUE);
        return true;
    }

    // ------------------------------------------------------------------ reporting
    /**
     * Fixes the outcome of a finished invocation. The status is the only source of truth: a matched
     * {@code @Test(expectedExceptions)} leaves a throwable on a passing result, and a failed retry
     * attempt is delivered as a skip - reading either naively would report a green test as broken or
     * a flaky one as skipped.
     */
    private void recordOutcome(ITestResult result) {
        try {
            if (!active() || result == null) {
                return;
            }
            Throwable error = result.getThrowable();
            String outcome;
            String message = null;
            String traces = null;
            if (result.getStatus() == ITestResult.SUCCESS) {
                outcome = Outcome.PASSED.wire();
            } else if (result.getStatus() == ITestResult.SKIP && !result.wasRetried()) {
                outcome = Outcome.SKIPPED.wire();
                message = skipMessage(result, error);
                traces = Outcomes.stackTrace(error);
            } else {
                // FAILURE, SUCCESS_PERCENTAGE_FAILURE, and a skip that is really a failed retry
                // attempt: every attempt is reported, they merge into one autotest as attempts
                outcome = Outcomes.failureOutcome(error);
                message = Outcomes.messageOf(error);
                traces = Outcomes.stackTrace(error);
            }
            long tEnd = result.getEndMillis() > 0L
                    ? result.getEndMillis()
                    : System.currentTimeMillis();
            if (!Invocations.outcome(TestRefs.key(result), outcome, message, traces, tEnd)) {
                // no open invocation: the listener was attached after this test started, or a
                // foreign listener replaced its result - report it now, without its fixtures
                reportUntracked(result, outcome, message, traces);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA testng: outcome handling failed for " + nameOf(result), t);
        }
    }

    /**
     * {@code @Test(enabled = false)} fires no event whatsoever, so the disabled tests are read off
     * the excluded methods of the {@code <test>}. That list also holds everything filtered out by
     * groups or {@code <methods><include>} - those are outside the run's scope by the host's own
     * choice and must stay unreported, hence the {@code getEnabled} check.
     */
    private void reportDisabledTests(ITestContext context) {
        Collection<ITestNGMethod> excluded = context.getExcludedMethods();
        if (excluded == null) {
            return;
        }
        for (ITestNGMethod method : excluded) {
            if (method == null || !method.isTest() || method.getEnabled()) {
                continue;
            }
            TestRef ref = TestRefs.fromMethod(method);
            // scoped by <test> block, exactly like an executed invocation: a class listed in two
            // blocks reports its tests twice, and its disabled ones must not be the exception
            RuntimeContext ctx = new RuntimeContext(
                    DISABLED_PREFIX + context.getName() + "#" + ref.methodKey());
            ctx.testRef = ref;
            ctx.tEnd = ctx.tStart;
            ctx.tags.addAll(TestRefs.groups(method));
            emit(ctx, Outcome.SKIPPED.wire(), "disabled with @Test(enabled = false)", null);
        }
    }

    /** Report an invocation the adapter never opened a context for (fixtures and steps are lost). */
    private void reportUntracked(ITestResult result, String outcome, String message, String traces) {
        // the buffered setup nodes have nothing to attach to and would stick to the next test
        Fixtures.dropBufferedSetup(result.getMethod());
        RuntimeContext ctx = new RuntimeContext(TestRefs.key(result));
        ctx.testRef = TestRefs.fromResult(result);
        if (result.getStartMillis() > 0L) {
            ctx.tStart = result.getStartMillis();
        }
        ctx.tEnd = result.getEndMillis() > 0L ? result.getEndMillis() : ctx.tStart;
        ctx.tags.addAll(TestRefs.groups(result.getMethod()));
        ctx.invocationParameters.addAll(TestRefs.invocationParameters(result));
        emit(ctx, outcome, message, traces);
    }

    private void emit(RuntimeContext ctx, String outcome, String message, String traces) {
        DoqaSession local = session;
        if (local == null || !local.enabled || ctx == null || !REPORTED.add(ctx.uniqueId)) {
            return;
        }
        try {
            ResultBuilder.Built built = ResultBuilder.build(ctx, outcome, message, traces,
                    local.uploader(), local::allowsId, local.config);
            local.report(built);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA testng: report failed for " + nameOf(ctx), e);
        }
    }

    private void flushClass(String fqcn) {
        try {
            session.flushClass(fqcn);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA testng: class flush failed for " + fqcn, e);
        }
    }

    private void flush() {
        try {
            session.flush();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA testng: batch flush failed", e);
        }
    }

    // ------------------------------------------------------------------ session
    /**
     * Fresh per-run state, then the session. The first run boundary wins: a nested TestNG run
     * started from inside a test (a suite of suites, Cucumber-TestNG) fires its own start events
     * while the outer run is in flight and must not wipe its state - it only reports into the same
     * process-wide session.
     */
    private void beginRun() {
        if (RUN_ACTIVE.compareAndSet(false, true)) {
            Invocations.reset();
            Fixtures.reset();
            ClassFixtures.reset();
            // plan positions are cached per test method: a second run in the same JVM (Gradle
            // daemon, IDE) may come with a different plan
            PlanSelection.reset();
            REPORTED.clear();
            SELECTION_WARNED.set(false);
        }
        session();
    }

    /** Drops all run state so that the next boundary starts clean - test seam for in-process runs. */
    static void resetState() {
        RUN_ACTIVE.set(false);
        SELECTION_WARNED.set(false);
        REPORTED.clear();
        Invocations.reset();
        Fixtures.reset();
        PlanSelection.reset();
        ClassFixtures.reset();
    }

    /**
     * The session, initialized on the first event this listener sees. Initialization cannot wait for
     * {@code onExecutionStart}: registration order decides which event arrives first, and with
     * {@code @Listeners} on a test class neither the execution nor the suite events are guaranteed.
     * A failed init leaves the session null and the adapter silent.
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
                    LOG.log(Level.WARNING, "DoQA testng: init failed, disabling", e);
                }
                initialized = true;
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
     * a renamed method or a moved class. Checked at the end, by what was reported: TestNG has no
     * single point before the run where the whole selection is known.
     */
    private void warnOnEmptySelection() {
        DoqaSession local = session;
        if (local == null || !local.enabled || local.runContext == null
                || local.runContext.selectedExternalIds() == null
                || local.runContext.selectedExternalIds().isEmpty()) {
            return;
        }
        if (REPORTED.isEmpty() && SELECTION_WARNED.compareAndSet(false, true)) {
            LOG.warning("DoQA: the run selects " + local.runContext.selectedExternalIds().size()
                    + " autotest(s), but none of them matched the discovered tests - nothing"
                    + " ran. The selected external ids are " + local.runContext.selectedExternalIds()
                    + "; re-report this suite to DoQA so the catalog picks up the current ids.");
        }
    }

    // ------------------------------------------------------------------ helpers
    /** The executed classes of one {@code <test>} - what to sweep and what to stream. */
    private static Set<String> classesOf(ITestContext context) {
        Set<String> classes = new LinkedHashSet<>();
        ITestNGMethod[] methods = context.getAllTestMethods();
        if (methods == null) {
            return classes;
        }
        for (ITestNGMethod method : methods) {
            if (method == null || method.getTestClass() == null
                    || method.getTestClass().getRealClass() == null) {
                continue;
            }
            classes.add(method.getTestClass().getRealClass().getName());
        }
        return classes;
    }

    /** Naming the methods that caused a skip is the difference between "skipped" and knowing why. */
    private static String skipMessage(ITestResult result, Throwable error) {
        String cause = Outcomes.messageOf(error);
        List<ITestNGMethod> causedBy = result.getSkipCausedBy();
        if (causedBy == null || causedBy.isEmpty()) {
            return cause;
        }
        StringBuilder joined = new StringBuilder("skipped because these methods did not succeed: ");
        for (int i = 0; i < causedBy.size(); i++) {
            if (i > 0) {
                joined.append(", ");
            }
            joined.append(causedBy.get(i) == null ? "?" : causedBy.get(i).getQualifiedName());
        }
        if (cause != null) {
            joined.append('\n').append(cause);
        }
        return joined.toString();
    }

    private static String nameOf(ITestResult result) {
        if (result == null || result.getMethod() == null) {
            return "an unnamed test";
        }
        return result.getMethod().getQualifiedName();
    }

    private static String nameOf(RuntimeContext ctx) {
        return ctx.testRef == null ? ctx.uniqueId : ctx.testRef.fullName();
    }

    private static String nameOf(ISuite suite) {
        return suite == null ? "an unnamed suite" : suite.getName();
    }

    private static String nameOf(ITestContext context) {
        return context == null ? "an unnamed context" : context.getName();
    }
}
