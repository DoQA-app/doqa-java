package app.doqa.core;

import app.doqa.client.RunContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Mode-0 selective execution, expressed without any framework types: which tests the run selected
 * and where each of them sits in the run's plan. Fed exclusively with the {@link TestRef}s the
 * adapter also reports under, so a test cannot be deselected under one externalId and reported
 * under another.
 *
 * <p>Everything is gated on {@link DoqaSession#discoverySelectionActive()} first: the callers run
 * while the host is still building the run, and forcing session init outside mode 0 would create a
 * test run on the server merely because an IDE listed the tests. Every failure is fail-open (the
 * test runs, the order stays as it was) - an adapter has no right to drop a test because its own
 * resolution broke.
 *
 * <p>Internal adapter API.
 */
public final class PlanSelection {

    private static final Logger LOG = Logger.getLogger(PlanSelection.class.getName());

    /** Plan position per executing test method; resolving one costs a full attribution pass. */
    private static final ConcurrentMap<String, Integer> PLAN_INDEX = new ConcurrentHashMap<>();

    private PlanSelection() {
    }

    /** True when the run selected a subset of autotests, i.e. deselection is meaningful. */
    public static boolean active() {
        return plan() != null;
    }

    /** True when the selection also carries a server-defined order to run it in. */
    public static boolean hasPlan() {
        RunContext plan = plan();
        return plan != null && plan.selectedOrder() != null && !plan.selectedOrder().isEmpty();
    }

    /**
     * Whether {@code ref} is part of the run. A placeholder {@code externalId} template
     * ({@code login_{browser}}) cannot be compared literally before the arguments are known: it is
     * included when any selected id matches its wildcard form, and the exact per-invocation ids are
     * re-checked at report time.
     */
    public static boolean allows(TestRef ref) {
        try {
            RunContext plan = plan();
            if (plan == null) {
                return true;
            }
            String externalId = Attribution.resolve(ref).externalId;
            if (Placeholders.hasPlaceholder(externalId)) {
                Pattern pattern = Placeholders.templateToRegex(externalId);
                for (String selected : plan.selectedExternalIds()) {
                    if (pattern.matcher(selected).matches()) {
                        return true;
                    }
                }
                return false;
            }
            return plan.allows(externalId);
        } catch (Throwable t) {
            // e.g. NoClassDefFoundError while introspecting a broken test class
            LOG.log(Level.WARNING, "DoQA " + AdapterRuntime.framework()
                    + ": selection failed to resolve, keeping the test", t);
            return true;
        }
    }

    /**
     * Whether a container whose tests pin their ids at execution time ({@code @TestFactory}) may
     * hold a selected autotest, matched by the runner identity the plan carries. Unknown identity
     * keeps the container.
     */
    public static boolean allowsRuntimeIdContainer(TestRef ref) {
        try {
            RunContext plan = plan();
            if (plan == null || ref == null) {
                return true;
            }
            Meta meta = MetaReader.read(ref);
            return plan.mayHoldSelected(ResultBuilder.namespaceOf(ref, meta, null),
                    ResultBuilder.classnameOf(ref, meta, null), ref.methodName);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA " + AdapterRuntime.framework()
                    + ": selection failed to resolve, keeping the container", t);
            return true;
        }
    }

    /**
     * Whether the run includes the test about to execute, judged by the externalId it will be
     * reported under: the runtime override ({@code Doqa.addExternalId}), else the annotation id,
     * with {@code {param}} placeholders substituted from the arguments captured so far. An id that
     * still carries a placeholder is kept.
     */
    public static boolean allowsInvocation(RuntimeContext ctx) {
        try {
            RunContext plan = plan();
            if (plan == null || ctx == null) {
                return true;
            }
            String declared = ctx.externalId;
            if (declared == null) {
                if (ctx.testRef == null) {
                    return true;
                }
                declared = Attribution.resolve(ctx.testRef).externalId;
            }
            String externalId = Placeholders.resolve(declared, Placeholders.paramsOf(ctx));
            if (externalId == null || Placeholders.hasPlaceholder(externalId)) {
                return true;
            }
            return plan.allows(externalId);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA " + AdapterRuntime.framework()
                    + ": selection failed to resolve, keeping the test", t);
            return true;
        }
    }

    /**
     * Aborts the running test when the selective run does not include it - skipped, not failed.
     * Only a real invocation is aborted; a fixture or hand-bound context is left alone.
     */
    public static void abortIfDeselected(RuntimeContext ctx, String externalId) {
        if (ctx == null || ctx.uniqueId == null || allowsInvocation(ctx)) {
            return;
        }
        RuntimeException skip = skipSignal(externalId);
        if (skip != null) {
            throw skip;
        }
    }

    /**
     * How to abort one invocation of a parameterized test now that its arguments are known, or
     * {@code null} when it stays in the run. Only placeholder templates ({@code login_{browser}})
     * are judged here - a literal id was already decided at discovery.
     */
    public static RuntimeException deselectedInvocationSignal(RuntimeContext ctx) {
        try {
            if (ctx == null || ctx.testRef == null || plan() == null) {
                return null;
            }
            String declared = ctx.externalId != null
                    ? ctx.externalId
                    : Attribution.resolve(ctx.testRef).externalId;
            if (!Placeholders.hasPlaceholder(declared) || allowsInvocation(ctx)) {
                return null;
            }
            return skipSignal(Placeholders.resolve(declared, Placeholders.paramsOf(ctx)));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA " + AdapterRuntime.framework()
                    + ": selection failed to resolve, keeping the invocation", t);
            return null;
        }
    }

    /** The framework's own "skip this test" exception, or {@code null} if none is registered. */
    private static RuntimeException skipSignal(String externalId) {
        return AdapterRuntime.skipSignal(
                externalId + " is not part of the DoQA run being executed");
    }

    /**
     * Position of {@code ref} in the run's plan (0-based); no plan or not in it =&gt;
     * {@link Integer#MAX_VALUE}, which a stable sort keeps at the tail. A placeholder template takes
     * the position of the first selected id matching its wildcard form, mirroring {@link #allows}.
     */
    public static int planIndex(TestRef ref) {
        try {
            RunContext plan = plan();
            List<String> order = plan == null ? null : plan.selectedOrder();
            if (order == null || order.isEmpty()) {
                return Integer.MAX_VALUE;
            }
            String cacheKey = cacheKey(ref);
            if (cacheKey == null) {
                return indexOf(plan, order, ref);
            }
            Integer cached = PLAN_INDEX.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            int index = indexOf(plan, order, ref);
            PLAN_INDEX.put(cacheKey, index);
            return index;
        } catch (Throwable t) {
            return Integer.MAX_VALUE; // unresolvable -> stable tail
        }
    }

    /**
     * Drops the plan-position cache at a run boundary. A long-lived JVM (Gradle daemon, IDE) can run
     * twice against different plans, and stale positions would order the second run by the first
     * one's plan; the entries also pin the test classes' {@code Method} objects.
     */
    public static void reset() {
        PLAN_INDEX.clear();
    }

    /**
     * Cache key of a resolved position. Keyed by the EXECUTING class and not by the {@link Method}:
     * an inherited test - the norm in TestNG suites - is one {@code Method} run by several classes,
     * and each of those has its own externalId and its own place in the plan.
     */
    private static String cacheKey(TestRef ref) {
        if (ref.testMethod == null || ref.fqcn == null) {
            return null;
        }
        return ref.fqcn + "#" + ref.methodName + "(" + (ref.methodParamTypes == null
                ? "" : ref.methodParamTypes) + ")";
    }

    private static int indexOf(RunContext plan, List<String> order, TestRef ref) {
        String externalId = Attribution.resolve(ref).externalId;
        if (externalId == null) {
            return Integer.MAX_VALUE;
        }
        if (Placeholders.hasPlaceholder(externalId)) {
            Pattern pattern = Placeholders.templateToRegex(externalId);
            for (int i = 0; i < order.size(); i++) {
                if (pattern.matcher(order.get(i)).matches()) {
                    return i;
                }
            }
            return Integer.MAX_VALUE;
        }
        return plan.orderIndex(externalId);
    }

    /** The selective run context, or {@code null} when this run selects nothing (all modes but 0). */
    private static RunContext plan() {
        if (!DoqaSession.discoverySelectionActive()) {
            return null;
        }
        DoqaSession session;
        try {
            session = DoqaSession.getOrInit();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA " + AdapterRuntime.framework()
                    + ": init failed, running without selection", t);
            return null;
        }
        if (!session.enabled || session.runContext == null
                || session.runContext.selectedExternalIds() == null) {
            // disabled, files sink (no run) or a non-selective mode: nothing to select.
            return null;
        }
        return session.runContext;
    }
}
