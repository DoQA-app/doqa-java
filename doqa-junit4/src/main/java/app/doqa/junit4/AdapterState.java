package app.doqa.junit4;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.runner.notification.RunNotifier;

/**
 * Process-wide state shared by the adapter's entry points. JUnit 4 has no service-loader
 * registration and no single owner of a run, so the listener may exist once (surefire property) or
 * several times (one per {@link RunNotifier}) and the pieces coordinate through statics.
 *
 * <p>Internal adapter API.
 */
final class AdapterState {

    /** Test keys already reported this run; guards against duplicate and synthetic reporting. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** Classes whose fixtures/phases are captured by {@link DoqaRunner}, not by the listener. */
    private static final Set<String> RUNNER_MANAGED = ConcurrentHashMap.newKeySet();
    /** Notifiers that already carry a self-registered listener (identity, weakly held). */
    private static final Map<RunNotifier, Boolean> CLAIMED_NOTIFIERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Set between the run boundaries, so that a nested run recognizes itself as one. */
    private static final AtomicBoolean RUN_ACTIVE = new AtomicBoolean();

    private static volatile boolean listenerActive;

    private AdapterState() {
    }

    /** False when {@code key} was already reported - the caller must skip it. */
    static boolean markReported(String key) {
        return key != null && REPORTED.add(key);
    }

    /** Lets {@code key} be reported again: a surefire rerun of a failed test is a result of its own. */
    static void clearReported(String key) {
        if (key != null) {
            REPORTED.remove(key);
        }
    }

    /**
     * True for the caller that takes the run boundary, false while a run is already in flight: a
     * nested run started from inside a test ({@code JUnitCore} in a meta-test) fires the run events
     * of its own and must not wipe the outer run's state - it reports into the same session.
     */
    static boolean beginRun() {
        return RUN_ACTIVE.compareAndSet(false, true);
    }

    static void endRun() {
        RUN_ACTIVE.set(false);
    }

    /** Set by the listener on its first event: an externally registered listener is in charge. */
    static void listenerActive() {
        listenerActive = true;
    }

    static boolean isListenerActive() {
        return listenerActive;
    }

    /**
     * Claims {@code notifier} for a self-registered listener; false when it already has one. Keyed
     * by identity so that a per-class notifier (Gradle) still gets its own listener.
     */
    static boolean claimNotifier(RunNotifier notifier) {
        if (notifier == null) {
            return false;
        }
        return CLAIMED_NOTIFIERS.put(notifier, Boolean.TRUE) == null;
    }

    static void markRunnerManaged(String classFqcn) {
        if (classFqcn != null) {
            RUNNER_MANAGED.add(classFqcn);
        }
    }

    static boolean isRunnerManaged(String classFqcn) {
        return classFqcn != null && RUNNER_MANAGED.contains(classFqcn);
    }

    /**
     * Fresh per-run state. Deliberately keeps {@link #RUNNER_MANAGED} and the claimed notifiers:
     * runners are built before the run starts (and live across a surefire rerun), so clearing
     * those would make the listener duplicate fixtures the runner already owns.
     */
    static void reset() {
        REPORTED.clear();
    }

    /** Full reset - test seam only. */
    static void resetAll() {
        REPORTED.clear();
        RUNNER_MANAGED.clear();
        CLAIMED_NOTIFIERS.clear();
        RUN_ACTIVE.set(false);
        listenerActive = false;
    }
}
