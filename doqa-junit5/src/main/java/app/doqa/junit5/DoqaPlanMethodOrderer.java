package app.doqa.junit5;

import app.doqa.core.AdapterRuntime;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

/**
 * Plan-driven {@link MethodOrderer}: sorts the test methods of a class by their position in the
 * DoQA run plan ({@code GET /test-runs/{id}/autotests}, server order). Stable sort - methods
 * outside the plan keep their relative order at the tail (mode-0 {@link DoqaSelectFilter} deselects
 * them anyway; ordering never changes what runs).
 *
 * <p><b>Strictly opt-in</b> (Jupiter offers no adapter hook to force an orderer): the user enables
 * it in {@code junit-platform.properties}:
 *
 * <pre>{@code
 * junit.jupiter.testmethod.order.default=app.doqa.junit5.DoqaPlanMethodOrderer
 * junit.jupiter.testclass.order.default=app.doqa.junit5.DoqaPlanClassOrderer
 * }</pre>
 *
 * <p>Limitations: Jupiter executes hierarchically by class - methods
 * cannot interleave across classes (class blocks; pair with {@link DoqaPlanClassOrderer});
 * Jupiter engine only; order is only meaningful with sequential execution
 * ({@code junit.jupiter.execution.parallel.enabled=false}, the default). Without an ordered
 * mode-0 plan this orderer is a strict no-op - no session init, no server calls, default order
 * preserved. Never throws into discovery.
 */
public class DoqaPlanMethodOrderer implements MethodOrderer {

    static {
        AdapterRuntime.configure("junit5", "junit-platform");
    }

    @Override
    public void orderMethods(MethodOrdererContext context) {
        try {
            if (!PlanOrdering.planActive()) {
                return; // no DoQA session / no ordered plan -> no-op
            }
            Map<MethodDescriptor, Integer> positions = new IdentityHashMap<>();
            for (MethodDescriptor descriptor : context.getMethodDescriptors()) {
                positions.put(descriptor,
                        PlanOrdering.methodIndex(descriptor.getMethod(), descriptor.getDisplayName()));
            }
            context.getMethodDescriptors().sort(Comparator.comparingInt(positions::get));
        } catch (Throwable t) {
            // ordering is best-effort; a failure must not break the user's discovery
        }
    }
}
