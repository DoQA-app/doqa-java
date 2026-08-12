package app.doqa.junit4;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.RuntimeContext;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * Optional {@code @RunWith(DoqaRunner.class)} replacement for JUnit 4's default runner. The run
 * listener alone already reports everything JUnit 4 publishes; this runner adds what only a runner
 * can observe:
 * <ul>
 *   <li>{@code @Before} / {@code @After} become individual step nodes in {@code setup_results} /
 *       {@code teardown_results} instead of blending into the test body;</li>
 *   <li>{@code @BeforeClass} / {@code @AfterClass} become one exact node per method (a listener can
 *       only bracket all of them as a single aggregate between suite events);</li>
 *   <li>mode-0 selective runs deselect physically - a test outside the run's autotest list is not
 *       executed at all - and the rest run in the order of the DoQA plan.</li>
 * </ul>
 *
 * <p>It also attaches the run listener when nothing else did (see
 * {@link DoqaRunListener#ensureRegistered}), so {@code @RunWith} alone is enough under Gradle and IDEs.
 */
public class DoqaRunner extends BlockJUnit4ClassRunner {

    static {
        AdapterRuntime.configure("junit4", "junit4");
    }

    private static final Logger LOG = Logger.getLogger(DoqaRunner.class.getName());

    public DoqaRunner(Class<?> klass) throws InitializationError {
        super(klass);
        // this runner records the class fixtures itself - the listener must not add its own node
        AdapterState.markRunnerManaged(klass.getName());
    }

    @Override
    public void run(RunNotifier notifier) {
        RunnerSupport.registerListener(notifier);
        super.run(notifier);
    }

    /**
     * Mode-0 deselection. Reported as "ignored" rather than removed from
     * {@link #computeTestMethods()} on purpose: an empty method list makes
     * {@link BlockJUnit4ClassRunner} fail initialization with "No runnable methods", i.e. a red
     * build for a class from which the run simply selected nothing. The listener stays silent about
     * the ignored test, whose id is not part of the run.
     */
    @Override
    protected boolean isIgnored(FrameworkMethod child) {
        if (RunnerSupport.deselected(describeChild(child))) {
            return true;
        }
        return super.isIgnored(child);
    }

    /** Test methods in the DoQA plan's order; the set is never changed, only the sequence. */
    @Override
    protected List<FrameworkMethod> computeTestMethods() {
        List<FrameworkMethod> methods = super.computeTestMethods();
        // see RunnerSupport.Refs: identity from the method, not from describeChild
        return RunnerSupport.planOrder(getTestClass(), methods,
                method -> TestRefs.fromMethod(getTestClass().getJavaClass(), method.getMethod()));
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        Statement base = super.methodInvoker(method, test);
        try {
            return RunnerSupport.phase(base, describeChild(method), RuntimeContext.Phase.CALL);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: steps of " + method.getName()
                    + " will not be bucketed by phase", t);
            return base;
        }
    }

    @Override
    protected Statement withBefores(FrameworkMethod method, Object target, Statement statement) {
        try {
            return RunnerSupport.fixtureSteps(statement,
                    getTestClass().getAnnotatedMethods(Before.class), target,
                    describeChild(method), true);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: @Before methods stay unreported", t);
            return super.withBefores(method, target, statement);
        }
    }

    @Override
    protected Statement withAfters(FrameworkMethod method, Object target, Statement statement) {
        try {
            return RunnerSupport.fixtureSteps(statement,
                    getTestClass().getAnnotatedMethods(After.class), target,
                    describeChild(method), false);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: @After methods stay unreported", t);
            return super.withAfters(method, target, statement);
        }
    }

    @Override
    protected Statement withBeforeClasses(Statement statement) {
        try {
            return RunnerSupport.classFixtureSteps(statement,
                    getTestClass().getAnnotatedMethods(BeforeClass.class), true,
                    getTestClass().getName());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: @BeforeClass methods stay unreported", t);
            return super.withBeforeClasses(statement);
        }
    }

    @Override
    protected Statement withAfterClasses(Statement statement) {
        try {
            return RunnerSupport.classFixtureSteps(statement,
                    getTestClass().getAnnotatedMethods(AfterClass.class), false,
                    getTestClass().getName());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: @AfterClass methods stay unreported", t);
            return super.withAfterClasses(statement);
        }
    }
}
