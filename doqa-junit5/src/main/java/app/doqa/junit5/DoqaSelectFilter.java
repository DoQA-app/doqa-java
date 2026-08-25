package app.doqa.junit5;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.PlanSelection;
import app.doqa.core.TestRef;
import java.util.Optional;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Mode-0 selective execution: at discovery, keep only the test methods whose resolved
 * {@code externalId} is in the run's selected list ({@code GET /test-runs/{id}/autotests}).
 * Auto-registered via
 * {@code META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter} (LauncherConfig enables
 * post-discovery-filter auto-registration by default).
 *
 * <p>Runs before the listener, so the core lazily initializes the session (which, for mode 0, fetches
 * the selective list). For any other mode / disabled session, everything is included (no filtering). Filtering is applied at the <em>method</em> descriptor granularity: a
 * {@code @ParameterizedTest} template is included/excluded as a whole (its externalId collapses to
 * method level), a {@code @TestFactory} container is kept only when the plan places a selected
 * autotest in it (its dynamic tests do not exist at discovery, so they are judged again as they
 * pin their ids), and class containers are always included so their children filter individually. Any resolution error includes the descriptor -
 * reporting machinery must never break the user's discovery.
 */
public class DoqaSelectFilter implements PostDiscoveryFilter {

    static {
        AdapterRuntime.configure("junit5", "junit-platform");
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        try {
            return applySafely(descriptor);
        } catch (Throwable t) {
            // e.g. NoClassDefFoundError raised while introspecting a broken test class
            return FilterResult.included("DoQA selection failed to resolve, keeping: " + t);
        }
    }

    private static FilterResult applySafely(TestDescriptor descriptor) {
        // gating on the resolved config avoids forcing session init in other modes: in mode 2 that
        // would create a run on the server merely because an IDE discovered the tests
        if (!PlanSelection.active()) {
            return FilterResult.included("DoQA mode-0 selection not active");
        }

        Optional<TestSource> sourceOpt = descriptor.getSource();
        if (!sourceOpt.isPresent() || !(sourceOpt.get() instanceof MethodSource)) {
            // class / engine containers and non-method sources are kept; children filter themselves.
            return FilterResult.included("not a test method");
        }
        MethodSource ms = (MethodSource) sourceOpt.get();
        TestRef ref = TestRefs.fromDescriptor(descriptor, ms);

        if (TestRefs.isFactoryContainer(descriptor.getUniqueId())) {
            // dynamic tests do not exist yet: the container is judged by where the plan places the
            // selected autotests, its cases again as they pin their ids
            if (PlanSelection.allowsRuntimeIdContainer(ref)) {
                return FilterResult.included("dynamic container; ids re-checked as they are pinned");
            }
            return FilterResult.excluded("deselected (no selected autotest lives in this factory): "
                    + ref.fullName());
        }

        // a placeholder template ("login_{browser}") is kept as a whole when any selected id
        // matches its wildcard form; its invocations are judged once the arguments are known
        if (PlanSelection.allows(ref)) {
            return FilterResult.included("selected: " + ref.fullName());
        }
        return FilterResult.excluded("deselected (not in run's autotest list): " + ref.fullName());
    }
}
