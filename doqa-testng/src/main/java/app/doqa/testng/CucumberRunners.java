package app.doqa.testng;

import app.doqa.core.AdapterRuntime;
import java.lang.reflect.Method;
import org.testng.ITestNGMethod;

/** Cucumber-TestNG runner classes: the plugin reports their scenarios, so the adapter skips them. */
final class CucumberRunners {

    private static final String PICKLE_WRAPPER = "io.cucumber.testng.PickleWrapper";
    private static final String ABSTRACT_RUNNER = "io.cucumber.testng.AbstractTestNGCucumberTests";
    private static final String PLUGIN = "app.doqa.cucumber.DoqaCucumberPlugin";

    private static volatile Boolean pluginOnClasspath;

    private CucumberRunners() {
    }

    static boolean reportedByPlugin(ITestNGMethod method) {
        return AdapterRuntime.cucumberPluginCreated() && isScenarioMethod(method);
    }

    static boolean isRunnerFixtureOfPlugin(ITestNGMethod method) {
        return AdapterRuntime.cucumberPluginCreated() && isRunnerFixture(method);
    }

    // TestNG intercepts before setUpClass creates the plugin, so the module on the classpath is enough
    static boolean keepInSelection(ITestNGMethod method) {
        return isScenarioMethod(method)
                && (AdapterRuntime.cucumberPluginCreated() || pluginOnClasspath());
    }

    static boolean isScenarioMethod(ITestNGMethod method) {
        Method javaMethod = javaMethod(method);
        if (javaMethod == null) {
            return false;
        }
        for (Class<?> type : javaMethod.getParameterTypes()) {
            if (PICKLE_WRAPPER.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRunnerFixture(ITestNGMethod method) {
        Method javaMethod = javaMethod(method);
        return javaMethod != null
                && ABSTRACT_RUNNER.equals(javaMethod.getDeclaringClass().getName())
                && (method.isBeforeClassConfiguration() || method.isAfterClassConfiguration());
    }

    private static Method javaMethod(ITestNGMethod method) {
        return method == null || method.getConstructorOrMethod() == null
                ? null
                : method.getConstructorOrMethod().getMethod();
    }

    private static boolean pluginOnClasspath() {
        Boolean known = pluginOnClasspath;
        if (known == null) {
            boolean found;
            try {
                Class.forName(PLUGIN, false, CucumberRunners.class.getClassLoader());
                found = true;
            } catch (ClassNotFoundException | LinkageError e) {
                found = false;
            }
            pluginOnClasspath = known = found;
        }
        return known;
    }
}
