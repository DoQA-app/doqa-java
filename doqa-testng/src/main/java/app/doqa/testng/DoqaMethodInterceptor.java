package app.doqa.testng;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.PlanSelection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;

/**
 * Mode-0 selective execution and run-plan ordering for TestNG. TestNG hands an
 * {@link IMethodInterceptor} every test method of a {@code <test>} block exactly once (after
 * {@code @BeforeTest}, before the first class starts) and runs whatever list comes back, in that
 * order - so this single hook does both the deselection and the ordering, whichever way the host
 * launches TestNG. Auto-registered via {@code META-INF/services/org.testng.ITestNGListener}
 * alongside the reporting listener; it is a class of its own because interceptors are NOT
 * deduplicated by TestNG, and a hand-written {@code @Listeners} on top of the service file would
 * otherwise intercept twice.
 *
 * <p>Outside mode 0 (no selective run, reporting off, files sink) this is a strict no-op: the very
 * same list comes back, neither filtered nor copied.
 *
 * <p>Two TestNG specifics shape what is achievable here:
 * <ul>
 *   <li>dropping a method a kept one depends on aborts the whole {@code <test>} block, so the
 *       transitive closure of dependencies is executed and gated away at report time instead;</li>
 *   <li>TestNG rebuilds the dependency graph AFTER the interceptor, so the plan order holds only
 *       within that graph's constraints - a dependency always runs before its dependent, whatever
 *       position the plan gave it. Priorities, on the other hand, do not survive: the returned order
 *       wins over {@code @Test(priority)}.</li>
 * </ul>
 *
 * <p>Idempotent by construction: TestNG chains interceptors, so a doubly registered adapter calls
 * this twice, and the second pass reproduces exactly the same subset in the same order.
 */
public class DoqaMethodInterceptor implements IMethodInterceptor {

    static {
        AdapterRuntime.configure("testng", "testng");
    }

    private static final Logger LOG = Logger.getLogger(DoqaMethodInterceptor.class.getName());

    /** Compiled {@code dependsOn*} values - TestNG matches each of them as a regexp. */
    private static final ConcurrentMap<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    @Override
    public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
        if (methods == null || methods.isEmpty()) {
            return methods;
        }
        try {
            if (!PlanSelection.active()) {
                return methods;
            }
            return select(methods, context);
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    "DoQA testng: selection failed, running the methods as declared", t);
            return methods;
        }
    }

    // ------------------------------------------------------------------ selection
    private static List<IMethodInstance> select(List<IMethodInstance> methods,
                                                ITestContext context) {
        Set<IMethodInstance> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IMethodInstance> deselected = new ArrayList<>();
        for (IMethodInstance instance : methods) {
            ITestNGMethod method = instance.getMethod();
            if (method == null || PlanSelection.allows(TestRefs.fromMethod(method))) {
                keep.add(instance);
            } else {
                deselected.add(instance);
            }
        }
        if (deselected.isEmpty()) {
            return planOrder(methods);
        }
        int selected = keep.size();
        int restored = restoreDependencies(keep, deselected);
        // rebuilt in the incoming order, so restored dependencies keep their declared position
        List<IMethodInstance> kept = new ArrayList<>(keep.size());
        for (IMethodInstance instance : methods) {
            if (keep.contains(instance)) {
                kept.add(instance);
            }
        }
        logSelection(context, methods.size(), selected, restored);
        return planOrder(kept);
    }

    // ------------------------------------------------------------------ dependencies
    /**
     * Pulls deselected methods back into {@code keep} until nothing kept depends on something
     * missing, and returns how many were restored. Iterates to a fixed point: a restored dependency
     * can itself depend on another deselected method.
     */
    private static int restoreDependencies(Set<IMethodInstance> keep,
                                           List<IMethodInstance> deselected) {
        int restored = 0;
        List<IMethodInstance> frontier = new ArrayList<>(keep);
        while (!frontier.isEmpty() && !deselected.isEmpty()) {
            List<IMethodInstance> added = new ArrayList<>();
            for (IMethodInstance dependent : frontier) {
                if (!hasDependencies(dependent.getMethod())) {
                    continue; // the common case: no scan over the deselected methods at all
                }
                Iterator<IMethodInstance> candidates = deselected.iterator();
                while (candidates.hasNext()) {
                    IMethodInstance candidate = candidates.next();
                    if (dependsOn(dependent.getMethod(), candidate.getMethod())) {
                        candidates.remove();
                        keep.add(candidate);
                        added.add(candidate);
                    }
                }
            }
            restored += added.size();
            frontier = added;
        }
        return restored;
    }

    private static boolean hasDependencies(ITestNGMethod method) {
        if (method == null) {
            return false;
        }
        String[] methods = method.getMethodsDependedUpon();
        String[] groups = method.getGroupsDependedUpon();
        return (methods != null && methods.length > 0) || (groups != null && groups.length > 0);
    }

    private static boolean dependsOn(ITestNGMethod dependent, ITestNGMethod candidate) {
        if (dependent == null || candidate == null) {
            return false;
        }
        return dependsOnMethod(dependent, candidate) || dependsOnGroupOf(dependent, candidate);
    }

    /**
     * Mirrors TestNG's own matching of {@code dependsOnMethods}: every value is a regexp, compared
     * against the plain method name when it carries no package, and against the qualified name
     * otherwise. Being too generous here only means running a method the run did not select (its
     * result is gated away); being too strict means an aborted {@code <test>} block.
     */
    private static boolean dependsOnMethod(ITestNGMethod dependent, ITestNGMethod candidate) {
        String[] specs = dependent.getMethodsDependedUpon();
        if (specs == null || specs.length == 0) {
            return false;
        }
        for (String spec : specs) {
            if (spec == null || spec.isEmpty()) {
                continue;
            }
            // '$' separates an inner class in a dependency value, it is not an end-of-input
            // anchor - TestNG escapes it the same way before matching.
            String regexp = spec.replace("$", "\\$");
            if (regexp.indexOf('.') < 0) {
                if (matches(regexp, candidate.getMethodName())) {
                    return true;
                }
                continue;
            }
            if (matches(regexp, declaredName(candidate))
                    || matches(regexp, executedName(candidate))) {
                return true;
            }
            // TestNG's own second attempt for a qualified value that matched nothing: the class part
            // is replaced with the executing class of the depending method, which is how a
            // dependency declared in a base class resolves for an inherited test.
            Class<?> owner = executingClass(dependent);
            if (owner != null) {
                String retry = owner.getName() + regexp.substring(regexp.lastIndexOf('.'));
                if (matches(retry, declaredName(candidate))
                        || matches(retry, executedName(candidate))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code dependsOnGroups} values are regexps, matched against a candidate's groups. */
    private static boolean dependsOnGroupOf(ITestNGMethod dependent, ITestNGMethod candidate) {
        String[] groups = dependent.getGroupsDependedUpon();
        String[] candidateGroups = candidate.getGroups();
        if (groups == null || groups.length == 0
                || candidateGroups == null || candidateGroups.length == 0) {
            return false;
        }
        for (String group : groups) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            for (String candidateGroup : candidateGroups) {
                if (matches(group, candidateGroup)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code declaringClass.method} - what TestNG compares a qualified dependency value against. */
    private static String declaredName(ITestNGMethod method) {
        Class<?> declaring = method.getRealClass();
        return declaring == null ? null : declaring.getName() + "." + method.getMethodName();
    }

    /** {@code executingClass.method} - the same method as seen through the class that runs it. */
    private static String executedName(ITestNGMethod method) {
        Class<?> executing = executingClass(method);
        return executing == null ? null : executing.getName() + "." + method.getMethodName();
    }

    private static Class<?> executingClass(ITestNGMethod method) {
        return method.getTestClass() != null
                ? method.getTestClass().getRealClass()
                : method.getRealClass();
    }

    private static boolean matches(String regexp, String value) {
        return value != null && pattern(regexp).matcher(value).matches();
    }

    private static Pattern pattern(String regexp) {
        return PATTERNS.computeIfAbsent(regexp, value -> {
            try {
                return Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                return Pattern.compile(Pattern.quote(value)); // a plain name, not a regexp
            }
        });
    }

    // ------------------------------------------------------------------ ordering
    /**
     * {@code methods} reordered by their position in the DoQA run plan; methods outside the plan keep
     * their relative order at the tail (stable sort). Never changes the SET of methods, and when the
     * run carries no order at all the incoming list is returned as it was.
     */
    private static List<IMethodInstance> planOrder(List<IMethodInstance> methods) {
        if (methods.size() < 2) {
            return methods;
        }
        // identity: nothing guarantees that two IMethodInstances of one method differ by equals,
        // and every list entry needs its own position.
        Map<IMethodInstance, Integer> positions = new IdentityHashMap<>();
        boolean planned = false;
        for (IMethodInstance instance : methods) {
            int index = instance.getMethod() == null
                    ? Integer.MAX_VALUE
                    : PlanSelection.planIndex(TestRefs.fromMethod(instance.getMethod()));
            positions.put(instance, index);
            planned = planned || index != Integer.MAX_VALUE;
        }
        if (!planned) {
            return methods;
        }
        List<IMethodInstance> ordered = new ArrayList<>(methods);
        Collections.sort(ordered, Comparator.comparingInt(positions::get));
        return ordered;
    }

    // ------------------------------------------------------------------ diagnostics
    private static void logSelection(ITestContext context, int total, int selected, int restored) {
        String where = context == null || context.getName() == null
                ? "<test>"
                : "<test> '" + context.getName() + "'";
        if (selected + restored == 0) {
            LOG.warning("DoQA testng: the run selected none of the " + total + " methods of " + where
                    + ", it will execute nothing - check that the run's autotest list belongs to"
                    + " this project");
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("DoQA testng: " + where + ": " + selected + " of " + total
                    + " methods selected, " + (total - selected - restored) + " deselected, "
                    + restored + " kept to satisfy dependencies");
        }
    }
}
