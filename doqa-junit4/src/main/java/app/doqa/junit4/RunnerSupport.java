package app.doqa.junit4;

import app.doqa.Doqa;
import app.doqa.core.ClassFixtures;
import app.doqa.core.DoqaContexts;
import app.doqa.core.Outcomes;
import app.doqa.core.PlanSelection;
import app.doqa.core.RuntimeContext;
import app.doqa.core.StepNode;
import app.doqa.core.TestRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.FixMethodOrder;
import org.junit.internal.runners.statements.RunAfters;
import org.junit.internal.runners.statements.RunBefores;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

/**
 * The mechanics shared by {@link DoqaRunner} and {@link DoqaRunnerWithParameters}: phase switching,
 * per-fixture step nodes, class-fixture recording, mode-0 deselection and plan ordering.
 *
 * <p>A runner only ever <em>binds</em> to a context the run listener already opened (JUnit 4 hands
 * the runner no place to open one: in 4.13 {@code methodBlock} is built lazily inside
 * {@code runLeaf}, i.e. between {@code testStarted} and {@code testFinished}). When
 * {@link DoqaContexts#bind} returns {@code null} - no listener, or the test was never opened - the
 * base {@link Statement} is executed untouched. There is deliberately no fallback to the thread's
 * "current" context: under a parallel scheduler that would attribute this test's fixture steps to
 * whichever test happens to own the thread.
 *
 * <p>Every adapter-side action is wrapped in {@code try/catch (Throwable)}: a throw escaping into
 * JUnit turns into a synthetic {@code Test mechanism} failure and reddens the build. The exception
 * of the test (or of the user's fixture) is always rethrown as-is.
 *
 * <p>Internal adapter API.
 */
final class RunnerSupport {

    private static final Logger LOG = Logger.getLogger(RunnerSupport.class.getName());

    /**
     * Identity of one child method. Resolved from the {@link FrameworkMethod}, not from
     * {@code describeChild}: {@code computeTestMethods} runs from the {@code ParentRunner}
     * constructor's validation pass, before the description cache field exists.
     */
    interface Refs {
        TestRef of(FrameworkMethod method);
    }

    private RunnerSupport() {
    }

    /** Attaches the run listener; a failure only means this run goes unreported. */
    static void registerListener(RunNotifier notifier) {
        try {
            DoqaRunListener.ensureRegistered(notifier);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: could not register the run listener", t);
        }
    }

    // ------------------------------------------------------------------ phases and fixtures

    /**
     * Binds this test's context on the current (test) thread and sets its phase, so that
     * {@code Doqa.step} / AspectJ {@code @Step} / attachments land in the right bucket. Returns the
     * bound context, or {@code null} when there is none.
     */
    static RuntimeContext bind(Description description, RuntimeContext.Phase phase) {
        try {
            RuntimeContext ctx = DoqaContexts.bind(TestRefs.key(description));
            if (ctx != null && phase != null) {
                ctx.phase = phase;
            }
            return ctx;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: could not bind the context of " + description, t);
            return null;
        }
    }

    /** {@code base} executed with the context bound and its phase set (CALL for the test body). */
    static Statement phase(final Statement base, final Description description,
                           final RuntimeContext.Phase phase) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                bind(description, phase);
                base.evaluate();
            }
        };
    }

    /**
     * Wraps each {@code @Before} / {@code @After} of {@code methods} into its own step node in the
     * setup / teardown bucket. The list is used exactly as JUnit built it, so the execution order
     * stays JUnit's own ({@code @Before} superclass-first, {@code @After} subclass-first) - this
     * replaces {@code withBefores} / {@code withAfters} instead of delegating to them, because the
     * per-method boundary is only observable inside {@code RunBefores.invokeMethod}.
     */
    static Statement fixtureSteps(Statement base, List<FrameworkMethod> methods, Object target,
                                  Description description, boolean before) {
        if (methods == null || methods.isEmpty()) {
            return base;
        }
        return before
                ? new StepBefores(base, methods, target, description)
                : new StepAfters(base, methods, target, description);
    }

    /**
     * Wraps each {@code @BeforeClass} / {@code @AfterClass} of {@code methods} into its own
     * {@link ClassFixtures} node under {@code classFqcn} - one node per method rather than a single
     * aggregate. A transient context holding the node is bound for the duration, so
     * {@code Doqa.step} / {@code @Step} / {@code Doqa.addAttachments} inside a class fixture nest
     * under it instead of being dropped.
     *
     * <p>{@code classFqcn} is the class the runner executes (not the literal declaring class of an
     * inherited fixture): {@link ClassFixtures} resolves a test's fixtures along the enclosing-class
     * chain, so a superclass key would never be found while the executing class also keeps a nested
     * class's fixtures out of the enclosing class's tests.
     */
    static Statement classFixtureSteps(Statement base, List<FrameworkMethod> methods,
                                       boolean before, String classFqcn) {
        if (methods == null || methods.isEmpty()) {
            return base;
        }
        return before
                ? new ClassFixtureBefores(base, methods, classFqcn)
                : new ClassFixtureAfters(base, methods, classFqcn);
    }

    // ------------------------------------------------------------------ selection and ordering

    /**
     * True when mode-0 selection is active and this test is not part of the run. The caller
     * reports it through {@code isIgnored}, so JUnit sends {@code testIgnored} and the listener
     * stays silent on it (report-time id gate).
     */
    static boolean deselected(Description description) {
        try {
            return PlanSelection.active()
                    && !PlanSelection.allows(TestRefs.fromDescription(description));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: selection check failed, keeping " + description, t);
            return false;
        }
    }

    /**
     * {@code methods} reordered by their position in the DoQA run plan; tests outside the plan keep
     * their relative order at the tail (stable sort). Never changes the SET of methods, and any
     * failure degrades to the default order.
     *
     * <p>A class carrying {@code @FixMethodOrder} is left alone: the author pinned the order
     * explicitly, and JUnit itself honours that annotation over any external sorting.
     */
    static List<FrameworkMethod> planOrder(TestClass testClass, List<FrameworkMethod> methods,
                                           Refs refs) {
        try {
            if (methods == null || methods.size() < 2 || !PlanSelection.hasPlan()
                    || hasFixedOrder(testClass)) {
                return methods;
            }
            // identity: two FrameworkMethods of the same Method are equal, but each list entry
            // needs its own position.
            Map<FrameworkMethod, Integer> positions = new IdentityHashMap<>();
            for (FrameworkMethod method : methods) {
                positions.put(method, PlanSelection.planIndex(refs.of(method)));
            }
            List<FrameworkMethod> ordered = new ArrayList<>(methods);
            Collections.sort(ordered, Comparator.comparingInt(positions::get));
            return ordered;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: plan ordering failed, keeping the default order", t);
            return methods;
        }
    }

    private static boolean hasFixedOrder(TestClass testClass) {
        return testClass != null && testClass.getJavaClass() != null
                && testClass.getJavaClass().getAnnotation(FixMethodOrder.class) != null;
    }

    // ------------------------------------------------------------------ internals

    private static void invokeAsStep(Description description, RuntimeContext.Phase phase,
                                     FrameworkMethod method, Doqa.ThrowingRunnable invocation)
            throws Throwable {
        RuntimeContext ctx = bind(description, phase);
        if (ctx == null) {
            invocation.run();
            return;
        }
        Doqa.step(method.getName(), invocation);
    }

    private static void invokeAsClassFixture(String classFqcn, boolean before,
                                             FrameworkMethod method,
                                             Doqa.ThrowingRunnable invocation) throws Throwable {
        Recording recording = openFixture(method.getName());
        if (recording == null) {
            invocation.run();
            return;
        }
        Throwable failure = null;
        try {
            invocation.run();
        } catch (Throwable t) {
            failure = t;
        } finally {
            closeFixture(recording, failure, classFqcn, before);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Recording openFixture(String title) {
        try {
            StepNode node = new StepNode(title);
            RuntimeContext fixtureCtx = new RuntimeContext(null);
            fixtureCtx.stepStack.push(node);
            return new Recording(node, DoqaContexts.push(fixtureCtx));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: could not record class fixture " + title, t);
            return null;
        }
    }

    private static void closeFixture(Recording recording, Throwable failure, String classFqcn,
                                     boolean before) {
        try {
            DoqaContexts.restore(recording.previous);
            StepNode node = recording.node;
            if (failure == null) {
                node.outcome = "passed";
            } else {
                node.outcome = Outcomes.failureOutcome(failure);
                node.message = node.message == null
                        ? Outcomes.messageOf(failure)
                        : node.message + "\n" + Outcomes.messageOf(failure);
            }
            node.durationMs = System.currentTimeMillis() - node.startMillis;
            if (before) {
                ClassFixtures.recordBefore(classFqcn, node);
            } else {
                ClassFixtures.recordAfter(classFqcn, node);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: could not record class fixture of " + classFqcn, t);
        }
    }

    /** An open class-fixture node plus the context to put back on the thread afterwards. */
    private static final class Recording {
        private final StepNode node;
        private final RuntimeContext previous;

        Recording(StepNode node, RuntimeContext previous) {
            this.node = node;
            this.previous = previous;
        }
    }

    private static final class StepBefores extends RunBefores {
        private final Description description;

        StepBefores(Statement next, List<FrameworkMethod> befores, Object target,
                    Description description) {
            super(next, befores, target);
            this.description = description;
        }

        @Override
        protected void invokeMethod(final FrameworkMethod method) throws Throwable {
            invokeAsStep(description, RuntimeContext.Phase.SETUP, method,
                    new Doqa.ThrowingRunnable() {
                        @Override
                        public void run() throws Throwable {
                            StepBefores.super.invokeMethod(method);
                        }
                    });
        }
    }

    private static final class StepAfters extends RunAfters {
        private final Description description;

        StepAfters(Statement next, List<FrameworkMethod> afters, Object target,
                   Description description) {
            super(next, afters, target);
            this.description = description;
        }

        @Override
        protected void invokeMethod(final FrameworkMethod method) throws Throwable {
            invokeAsStep(description, RuntimeContext.Phase.TEARDOWN, method,
                    new Doqa.ThrowingRunnable() {
                        @Override
                        public void run() throws Throwable {
                            StepAfters.super.invokeMethod(method);
                        }
                    });
        }
    }

    private static final class ClassFixtureBefores extends RunBefores {
        private final String classFqcn;

        ClassFixtureBefores(Statement next, List<FrameworkMethod> befores, String classFqcn) {
            super(next, befores, null);
            this.classFqcn = classFqcn;
        }

        @Override
        protected void invokeMethod(final FrameworkMethod method) throws Throwable {
            invokeAsClassFixture(classFqcn, true, method, new Doqa.ThrowingRunnable() {
                @Override
                public void run() throws Throwable {
                    ClassFixtureBefores.super.invokeMethod(method);
                }
            });
        }
    }

    private static final class ClassFixtureAfters extends RunAfters {
        private final String classFqcn;

        ClassFixtureAfters(Statement next, List<FrameworkMethod> afters, String classFqcn) {
            super(next, afters, null);
            this.classFqcn = classFqcn;
        }

        @Override
        protected void invokeMethod(final FrameworkMethod method) throws Throwable {
            invokeAsClassFixture(classFqcn, false, method, new Doqa.ThrowingRunnable() {
                @Override
                public void run() throws Throwable {
                    ClassFixtureAfters.super.invokeMethod(method);
                }
            });
        }
    }
}
