package app.doqa.junit4;

import app.doqa.core.AdapterRuntime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

/** Scenarios of cucumber-junit, recognized by position: their descriptions carry no test class. */
final class CucumberSuites {

    private static final String CUCUMBER_RUNNER = "io.cucumber.junit.Cucumber";

    private static final Set<Description> NODES = ConcurrentHashMap.newKeySet();

    private CucumberSuites() {
    }

    static void collect(Description description) {
        if (description == null) {
            return;
        }
        if (isCucumberRunner(description.getTestClass())) {
            addSubtree(description);
            return;
        }
        for (Description child : description.getChildren()) {
            collect(child);
        }
    }

    static boolean reportedByPlugin(Description description) {
        return description != null && AdapterRuntime.cucumberPluginCreated()
                && NODES.contains(description);
    }

    static void reset() {
        NODES.clear();
    }

    private static void addSubtree(Description description) {
        if (!NODES.add(description)) {
            return;
        }
        for (Description child : description.getChildren()) {
            addSubtree(child);
        }
    }

    private static boolean isCucumberRunner(Class<?> testClass) {
        if (testClass == null) {
            return false;
        }
        RunWith runWith = testClass.getAnnotation(RunWith.class);
        return runWith != null && CUCUMBER_RUNNER.equals(runWith.value().getName());
    }
}
