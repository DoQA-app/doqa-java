package app.doqa.junit5;

import app.doqa.core.AdapterRuntime;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

/**
 * Plan-driven {@link ClassOrderer}: sorts test classes by the minimum plan position of their methods
 * (inter-class method interleaving is impossible in Jupiter - classes run as blocks). Stable sort
 * - classes with no planned method keep their relative order at the tail. Pair with
 * {@link DoqaPlanMethodOrderer} for in-class method order.
 *
 * <p><b>Strictly opt-in</b> via {@code junit-platform.properties}:
 * {@code junit.jupiter.testclass.order.default=app.doqa.junit5.DoqaPlanClassOrderer}.
 * Without an ordered mode-0 plan this orderer is a strict no-op - no session init, no server
 * calls, default order preserved. Never throws into discovery.
 */
public class DoqaPlanClassOrderer implements ClassOrderer {

    static {
        AdapterRuntime.configure("junit5", "junit-platform");
    }

    @Override
    public void orderClasses(ClassOrdererContext context) {
        try {
            if (!PlanOrdering.planActive()) {
                return; // no DoQA session / no ordered plan -> no-op
            }
            Map<ClassDescriptor, Integer> positions = new IdentityHashMap<>();
            for (ClassDescriptor descriptor : context.getClassDescriptors()) {
                positions.put(descriptor, PlanOrdering.classIndex(descriptor.getTestClass()));
            }
            context.getClassDescriptors().sort(Comparator.comparingInt(positions::get));
        } catch (Throwable t) {
            // ordering is best-effort; a failure must not break the user's discovery
        }
    }
}
