package app.doqa.junit5;

import app.doqa.core.PlanSelection;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Nested;

/**
 * Shared plan-position resolution for {@link DoqaPlanMethodOrderer} / {@link DoqaPlanClassOrderer}.
 * Positions come from {@link PlanSelection} in the shared core, which resolves and caches them -
 * the class orderer scans every method of every class.
 *
 * <p>Ordering never changes the set of tests: ids outside the plan get
 * {@link Integer#MAX_VALUE} and stay at the tail of a stable sort; any resolution failure degrades
 * to "no reordering" rather than breaking the build.
 */
final class PlanOrdering {

    private PlanOrdering() {
    }

    /**
     * True when the run carries a server-ordered plan; otherwise the orderers are strict no-ops.
     * The gate lives in the core: outside mode 0 the orderers must not force session init - Jupiter
     * applies them at DISCOVERY, and in mode 2 that would create a run on the server merely because
     * an IDE discovered the tests.
     */
    static boolean planActive() {
        return PlanSelection.hasPlan();
    }

    /**
     * Plan position of one test method (0-based); not in the plan =&gt; {@link Integer#MAX_VALUE}.
     * A placeholder externalId template ({@code login_{browser}}) takes the position of the first
     * selected id matching its wildcard form, mirroring {@link DoqaSelectFilter}'s template match.
     */
    static int methodIndex(Method method, String displayName) {
        try {
            return PlanSelection.planIndex(TestRefs.fromMethod(method, displayName));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE; // unresolvable -> stable tail
        }
    }

    /**
     * Min plan position across the class's methods - declared, inherited and {@code @Nested} (a
     * top-level class whose planned tests live only in nested classes must still sort by them):
     * inter-class interleaving is impossible in Jupiter (classes run as blocks), so a class sorts
     * by its best (minimal) method position.
     */
    static int classIndex(Class<?> testClass) {
        int best = Integer.MAX_VALUE;
        try {
            for (Class<?> c = testClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method method : declaredMethods(c)) {
                    if (method.isSynthetic()) {
                        continue;
                    }
                    best = Math.min(best, methodIndex(method, null));
                    if (best == 0) {
                        return 0;
                    }
                }
            }
            for (Class<?> nested : testClass.getDeclaredClasses()) {
                if (nested.isAnnotationPresent(Nested.class)) {
                    best = Math.min(best, classIndex(nested));
                    if (best == 0) {
                        return 0;
                    }
                }
            }
        } catch (Throwable t) {
            // broken classpath while introspecting: keep whatever position is known
        }
        return best;
    }

    private static Method[] declaredMethods(Class<?> c) {
        try {
            return c.getDeclaredMethods();
        } catch (Throwable t) {
            // NoClassDefFoundError from a method signature referencing an absent class
            return new Method[0];
        }
    }

    static void reset() {
        PlanSelection.reset();
    }
}
