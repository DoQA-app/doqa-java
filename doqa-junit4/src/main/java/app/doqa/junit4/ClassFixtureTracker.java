package app.doqa.junit4;

import app.doqa.client.Outcome;
import app.doqa.core.ClassFixtures;
import app.doqa.core.DoqaContexts;
import app.doqa.core.Outcomes;
import app.doqa.core.RuntimeContext;
import app.doqa.core.StepNode;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * The open class-fixture nodes ({@code @BeforeClass} / {@code @AfterClass}) of the run. JUnit 4
 * offers no hook around class fixtures, so they are timed between events instead: {@code @BeforeClass}
 * runs after {@code testSuiteStarted} and before the first {@code testStarted}, {@code @AfterClass}
 * after the last {@code testFinished} and before {@code testSuiteFinished}. A node is therefore
 * opened on one event and closed on another - which is why the pending nodes live here rather than
 * in a local variable - and lands in {@link ClassFixtures}, from where the session attaches it to
 * every test of the class.
 *
 * <p>While a node is open, a transient {@link RuntimeContext} carrying it as the open step is bound
 * to the thread, so steps and attachments taken inside a class fixture nest under the node.
 *
 * <p>Limits of timing a fixture between two events:
 * <ul>
 *   <li>the duration also covers the runner's own work in that window (test class construction,
 *       {@code @ClassRule} evaluation);</li>
 *   <li>under surefire's parallel provider the suite events arrive on the main thread while the
 *       tests run on pool threads, so the thread-bound context cannot be handed over - steps inside
 *       class fixtures are not guaranteed there;</li>
 *   <li>a fixture is recorded at most once per class: a surefire rerun re-runs {@code @BeforeClass}.</li>
 * </ul>
 *
 * <p>Internal adapter API.
 */
final class ClassFixtureTracker {

    /** One open fixture node plus what it takes to unwind the thread it was opened on. */
    private static final class Pending {
        final StepNode node;
        final RuntimeContext previous;
        final Thread owner;

        Pending(StepNode node, RuntimeContext previous, Thread owner) {
            this.node = node;
            this.previous = previous;
            this.owner = owner;
        }
    }

    private static final ConcurrentMap<String, Pending> PENDING_BEFORE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Pending> PENDING_AFTER = new ConcurrentHashMap<>();
    private static final Set<String> RECORDED_BEFORE = ConcurrentHashMap.newKeySet();
    private static final Set<String> RECORDED_AFTER = ConcurrentHashMap.newKeySet();

    private ClassFixtureTracker() {
    }

    /** Starts timing the class's {@code @BeforeClass} block (call only when it has one). */
    static void openBefore(String classFqcn, Class<?> testClass) {
        open(PENDING_BEFORE, classFqcn, title(testClass, BeforeClass.class, "@BeforeClass"));
    }

    /** Starts timing the class's {@code @AfterClass} block (call only when it has one). */
    static void openAfter(String classFqcn, Class<?> testClass) {
        open(PENDING_AFTER, classFqcn, title(testClass, AfterClass.class, "@AfterClass"));
    }

    /**
     * Closes the {@code @BeforeClass} node and records it; a no-op when the class has no open node
     * (no {@code @BeforeClass}, or already closed by an earlier test of the same class).
     */
    static void closeBefore(String classFqcn) {
        close(PENDING_BEFORE, RECORDED_BEFORE, classFqcn, true);
    }

    /** Closes the {@code @AfterClass} node and records it; a no-op when there is none open. */
    static void closeAfter(String classFqcn) {
        close(PENDING_AFTER, RECORDED_AFTER, classFqcn, false);
    }

    /** A class-level failure means the {@code @BeforeClass} block itself blew up. */
    static void failBefore(String classFqcn, Throwable error) {
        mark(PENDING_BEFORE, classFqcn, Outcomes.failureOutcome(error), Outcomes.messageOf(error));
    }

    /** A class-level assumption failure: {@code @BeforeClass} declared the class not applicable. */
    static void skipBefore(String classFqcn, Throwable error) {
        mark(PENDING_BEFORE, classFqcn, Outcome.SKIPPED.wire(), Outcomes.messageOf(error));
    }

    /** Marks a failing {@code @AfterClass}; true when there was such a node to mark. */
    static boolean failAfter(String classFqcn, Throwable error) {
        return mark(PENDING_AFTER, classFqcn, Outcomes.failureOutcome(error),
                Outcomes.messageOf(error));
    }

    /** Fresh per-run state; paired with {@link ClassFixtures#reset()}, which drops the nodes. */
    static void reset() {
        PENDING_BEFORE.clear();
        PENDING_AFTER.clear();
        RECORDED_BEFORE.clear();
        RECORDED_AFTER.clear();
    }

    // ------------------------------------------------------------------ pending nodes
    private static void open(ConcurrentMap<String, Pending> pendingByClass, String classFqcn,
                             String title) {
        if (classFqcn == null) {
            return;
        }
        // An @AfterClass node is re-opened after every test of the class (there is no "last test"
        // event): unwind the superseded one first, so pushes and restores stay properly nested.
        unbind(pendingByClass.remove(classFqcn));
        StepNode node = new StepNode(title);
        RuntimeContext fixtureCtx = new RuntimeContext(null);
        fixtureCtx.stepStack.push(node);
        RuntimeContext previous = DoqaContexts.push(fixtureCtx);
        pendingByClass.put(classFqcn, new Pending(node, previous, Thread.currentThread()));
    }

    private static void close(ConcurrentMap<String, Pending> pendingByClass, Set<String> recorded,
                             String classFqcn, boolean before) {
        if (classFqcn == null) {
            return;
        }
        Pending pending = pendingByClass.remove(classFqcn);
        if (pending == null) {
            return;
        }
        unbind(pending);
        StepNode node = pending.node;
        node.durationMs = System.currentTimeMillis() - node.startMillis;
        if (node.outcome == null) {
            node.outcome = Outcome.PASSED.wire();
        }
        if (!recorded.add(classFqcn)) {
            return;
        }
        if (before) {
            ClassFixtures.recordBefore(classFqcn, node);
        } else {
            ClassFixtures.recordAfter(classFqcn, node);
        }
    }

    private static boolean mark(ConcurrentMap<String, Pending> pendingByClass, String classFqcn,
                               String outcome, String message) {
        Pending pending = classFqcn == null ? null : pendingByClass.get(classFqcn);
        if (pending == null) {
            return false;
        }
        pending.node.outcome = outcome;
        // never lose the cause: appended after any note the fixture itself added
        pending.node.message = pending.node.message == null
                ? message
                : pending.node.message + "\n" + message;
        return true;
    }

    /**
     * Restores the thread-local only on the thread the node was opened on: the closing event may
     * arrive on another thread, where restoring would clobber somebody else's binding.
     */
    private static void unbind(Pending pending) {
        if (pending != null && pending.owner == Thread.currentThread()) {
            DoqaContexts.restore(pending.previous);
        }
    }

    /** One fixture method names the node after itself; several share a node named after the annotation. */
    private static String title(Class<?> testClass, Class<? extends Annotation> annotation,
                               String fallback) {
        List<Method> methods = Reflections.annotatedMethods(testClass, annotation);
        return methods.size() == 1 ? methods.get(0).getName() : fallback;
    }
}
