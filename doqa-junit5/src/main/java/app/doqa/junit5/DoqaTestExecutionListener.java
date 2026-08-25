package app.doqa.junit5;

import app.doqa.client.Outcome;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.ClassFixtures;
import app.doqa.core.DoqaContexts;
import app.doqa.core.DoqaSession;
import app.doqa.core.Outcomes;
import app.doqa.core.ResultBuilder;
import app.doqa.core.RuntimeContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.opentest4j.TestAbortedException;

/**
 * Thick JUnit Platform {@link TestExecutionListener} for DoQA. Inits the client + run (mode) once
 * at test-plan start; on each finished test, builds an {@code AutotestDef} + {@code AutotestResult}
 * from the collected {@link RuntimeContext} and attribution, then hands it to the session
 * (batched, streamed per class in realtime, or written to files). Auto-registered via
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}. Reporting failures
 * are logged as warnings and never rethrown into the build.
 *
 * <p>Container events are reported too, so results are not lost when tests never start on their own:
 * a container that FAILS/ABORTS (e.g. {@code @BeforeAll} threw) reports its descendant tests as
 * failed/skipped, and a skipped container (e.g. {@code @Disabled} on the class) reports every
 * descendant test as skipped. A per-plan reported set prevents double-reporting. A finished
 * top-level class container triggers the realtime per-class flush (its {@code @AfterAll} is
 * complete by then).
 */
public class DoqaTestExecutionListener implements TestExecutionListener {

    static {
        AdapterRuntime.configure("junit5", "junit-platform");
        AdapterRuntime.configureSkipSignal(TestAbortedException::new);
    }

    private static final Logger LOG = Logger.getLogger(DoqaTestExecutionListener.class.getName());

    private volatile DoqaSession session;
    private volatile TestPlan testPlan;
    /** uniqueIds already reported this plan; guards against container/test double-reporting. */
    private volatile Set<String> reported = ConcurrentHashMap.newKeySet();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.testPlan = testPlan;
        this.reported = ConcurrentHashMap.newKeySet();
        // fresh fixture/reflection state per plan: a surefire rerun must not accumulate
        // duplicate @BeforeAll or resolve against stale classes.
        ClassFixtures.reset();
        Reflections.reset();
        PlanOrdering.reset();
        try {
            this.session = DoqaSession.getOrInit();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA junit5: init failed, disabling", e);
        }
        warnOnEmptySelection(testPlan);
    }

    /**
     * A selective run that matched nothing executes zero tests and still looks green, so say it
     * out loud: the run's external ids and the ones resolved here have drifted apart - typically
     * a renamed display name or a moved class.
     */
    private void warnOnEmptySelection(TestPlan plan) {
        DoqaSession local = session;
        if (local == null || !local.enabled || local.runContext == null
                || local.runContext.selectedExternalIds() == null
                || local.runContext.selectedExternalIds().isEmpty()) {
            return;
        }
        if (plan != null && plan.countTestIdentifiers(DoqaTestExecutionListener::mayExecute) == 0) {
            LOG.warning("DoQA: the run selects " + local.runContext.selectedExternalIds().size()
                    + " autotest(s), but none of them matched the discovered tests - nothing will"
                    + " run. The selected external ids are " + local.runContext.selectedExternalIds()
                    + "; re-report this suite to DoQA so the catalog picks up the current ids.");
        }
    }

    /** A test, or a container that produces its tests only while it runs (template / factory). */
    private static boolean mayExecute(TestIdentifier id) {
        return id.isTest()
                || id.getSource().filter(source -> source instanceof MethodSource).isPresent();
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (!active() || !id.isTest()) {
            return;
        }
        RuntimeContext ctx = DoqaContexts.open(id.getUniqueId());
        ctx.testRef = TestRefs.fromIdentifier(id);
        ctx.tStart = System.currentTimeMillis();
        mergeNativeTags(ctx, id);
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (!active()) {
            return;
        }
        if (id.isTest()) {
            reportSkipped(id, reason);
            return;
        }
        // A skipped container (e.g. @Disabled on the class) fires one event but no child events;
        // expand it so each test is still reported as skipped.
        for (TestIdentifier child : testPlan.getDescendants(id)) {
            if (child.isTest()) {
                reportSkipped(child, reason);
            }
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!active()) {
            return;
        }
        if (id.isTest()) {
            reportFinishedTest(id, result);
            return;
        }
        // A container that failed/aborted (e.g. @BeforeAll threw) never starts its children;
        // report the descendants that have not reported yet so their results are not lost.
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            for (TestIdentifier child : testPlan.getDescendants(id)) {
                if (child.isTest()) {
                    reportSyntheticFromContainer(child, result);
                }
            }
        }
        // realtime: a finished TOP-LEVEL class container means its @AfterAll ran - stream the
        // class's results with complete fixtures.
        String topClass = topLevelClassOf(id);
        if (topClass != null) {
            try {
                session.flushClass(topClass);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "DoQA junit5: class flush failed for " + topClass, e);
            }
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!active()) {
            return;
        }
        try {
            session.flush();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA junit5: batch flush failed", e);
        }
    }

    // ------------------------------------------------------------------ reporting
    private void reportSkipped(TestIdentifier id, String reason) {
        if (!markReported(id)) {
            return;
        }
        try {
            RuntimeContext ctx = new RuntimeContext(id.getUniqueId());
            ctx.testRef = TestRefs.fromIdentifier(id);
            ctx.tEnd = ctx.tStart;
            mergeNativeTags(ctx, id);
            emit(ctx, Outcome.SKIPPED.wire(), reason, null);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA junit5: skip report failed for " + id.getDisplayName(), e);
        }
    }

    private void reportFinishedTest(TestIdentifier id, TestExecutionResult result) {
        if (!markReported(id)) {
            return;
        }
        try {
            RuntimeContext ctx = DoqaContexts.remove(id.getUniqueId());
            if (ctx == null) {
                ctx = new RuntimeContext(id.getUniqueId());
                ctx.testRef = TestRefs.fromIdentifier(id);
                mergeNativeTags(ctx, id);
            }
            ctx.tEnd = System.currentTimeMillis();
            emit(ctx, result);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA junit5: report failed for " + id.getDisplayName(), e);
        }
    }

    /** Report a descendant test of a failed/aborted container (it never ran). */
    private void reportSyntheticFromContainer(TestIdentifier id, TestExecutionResult containerResult) {
        if (!markReported(id)) {
            return;
        }
        try {
            RuntimeContext ctx = new RuntimeContext(id.getUniqueId());
            ctx.testRef = TestRefs.fromIdentifier(id);
            ctx.tEnd = ctx.tStart;
            mergeNativeTags(ctx, id);
            emit(ctx, containerResult);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA junit5: container-child report failed for "
                    + id.getDisplayName(), e);
        }
    }

    private void emit(RuntimeContext ctx, TestExecutionResult result) {
        String message = null;
        String traces = null;
        String outcome;
        switch (result.getStatus()) {
            case SUCCESSFUL:
                outcome = Outcome.PASSED.wire();
                break;
            case ABORTED:
                outcome = Outcome.SKIPPED.wire();
                Throwable ab = result.getThrowable().orElse(null);
                message = Outcomes.messageOf(ab);
                traces = Outcomes.stackTrace(ab);
                break;
            case FAILED:
            default:
                Throwable t = result.getThrowable().orElse(null);
                outcome = Outcomes.isAssertion(t) ? Outcome.FAILED.wire() : Outcome.BROKEN.wire();
                message = Outcomes.messageOf(t);
                traces = Outcomes.stackTrace(t);
                break;
        }
        emit(ctx, outcome, message, traces);
    }

    private void emit(RuntimeContext ctx, String outcome, String message, String traces) {
        ResultBuilder.Built built = ResultBuilder.build(
                ctx, outcome, message, traces, session.uploader(), session::allowsId, session.config);
        session.report(built);
    }

    // ------------------------------------------------------------------ helpers
    private boolean markReported(TestIdentifier id) {
        return reported.add(id.getUniqueId());
    }

    private boolean active() {
        return session != null && session.enabled;
    }

    /** Native JUnit {@code @Tag}s join the DoQA tags - one tagging, both mechanics see it. */
    private static void mergeNativeTags(RuntimeContext ctx, TestIdentifier id) {
        id.getTags().forEach(tag -> ctx.tags.add(tag.getName()));
    }

    /** FQCN when {@code id} is a TOP-LEVEL class container, else null. */
    private static String topLevelClassOf(TestIdentifier id) {
        Optional<TestSource> source = id.getSource();
        if (!source.isPresent() || !(source.get() instanceof ClassSource)) {
            return null;
        }
        String fqcn = ((ClassSource) source.get()).getClassName();
        return fqcn.equals(DoqaSession.topLevelClass(fqcn)) ? fqcn : null;
    }
}
