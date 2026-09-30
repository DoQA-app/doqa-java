package app.doqa.cucumber;

import app.doqa.client.DoqaConfig;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.DoqaSession;
import app.doqa.core.PlanSelection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/** JUnit Platform side of the Cucumber adapter: warns when the plugin is not enabled. */
public class DoqaCucumberExecutionListener implements TestExecutionListener {

    private static final Logger LOG = Logger.getLogger(DoqaCucumberExecutionListener.class.getName());
    private static final String JUNIT5_LISTENER = "app.doqa.junit5.DoqaTestExecutionListener";
    private static final AtomicBoolean WARNED_MISSING_PLUGIN = new AtomicBoolean();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        try {
            warnOnEmptySelection(testPlan);
        } catch (Throwable ignored) {
            // a diagnostic must never break the run
        }
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        try {
            if (!id.isTest() || AdapterRuntime.cucumberPluginCreated() || !isCucumber(id)
                    || reportingOff() || !WARNED_MISSING_PLUGIN.compareAndSet(false, true)) {
                return;
            }
            LOG.warning("DoQA cucumber: Cucumber scenarios are running without the DoQA plugin, so"
                    + " they are not reported by doqa-cucumber. Add it to the Cucumber plugins:"
                    + " cucumber.plugin=" + DoqaCucumberPlugin.class.getName()
                    + " in src/test/resources/junit-platform.properties (comma-separated with the"
                    + " plugins already listed there), or to the value of"
                    + " @ConfigurationParameter(key = \"cucumber.plugin\") on the @Suite class if"
                    + " it sets the plugins - that value overrides the file.");
        } catch (Throwable ignored) {
            // a diagnostic must never break the run
        }
    }

    private static void warnOnEmptySelection(TestPlan plan) {
        if (junit5AdapterPresent() || !PlanSelection.active()
                || plan.countTestIdentifiers(TestIdentifier::isTest) > 0) {
            return;
        }
        DoqaSession session = DoqaSession.getOrInit();
        Set<String> selected = session.runContext == null ? null
                : session.runContext.selectedExternalIds();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        LOG.warning("DoQA: the run selects " + selected.size() + " autotest(s), but none of them"
                + " matched the discovered scenarios - nothing will run. The selected external ids"
                + " are " + selected + "; re-report these features to DoQA so the catalog picks up"
                + " the current ids.");
    }

    private static boolean isCucumber(TestIdentifier id) {
        for (UniqueId.Segment segment : UniqueId.parse(id.getUniqueId()).getSegments()) {
            if ("engine".equals(segment.getType()) && "cucumber".equals(segment.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean reportingOff() {
        DoqaConfig config = DoqaSession.currentConfig();
        return config != null && DoqaConfig.REPORTING_OFF.equals(config.effectiveReporting());
    }

    private static boolean junit5AdapterPresent() {
        try {
            Class.forName(JUNIT5_LISTENER, false, DoqaCucumberExecutionListener.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
