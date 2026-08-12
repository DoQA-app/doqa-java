package app.doqa.junit4;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.PlanSelection;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Skips the tests a selective (mode 0) DoQA run did not select, for suites that cannot use
 * {@link DoqaRunner} because the class already has a runner of its own ({@code SpringRunner},
 * {@code MockitoJUnitRunner}, {@code Parameterized}, ...).
 *
 * <p>Registration: a {@code @Rule} field (per test) or a {@code @ClassRule} field (whole class).
 *
 * <p>A rule cannot remove a test from the run, so a deselected one is aborted with an assumption
 * failure: its body never executes, JUnit reports it as skipped and the adapter says nothing about
 * it (its id is not part of the run). Outside mode 0 - and whenever resolution fails - the rule is a
 * strict pass-through: it never withholds a test because the adapter itself broke.
 */
public class DoqaSelectRule implements TestRule {

    static {
        AdapterRuntime.configure("junit4", "junit4");
    }

    private static final Logger LOG = Logger.getLogger(DoqaSelectRule.class.getName());

    @Override
    public Statement apply(Statement base, Description description) {
        try {
            if (description == null || !PlanSelection.active()) {
                return base;
            }
            if (description.getMethodName() != null) {
                return PlanSelection.allows(TestRefs.fromDescription(description))
                        ? base
                        : skip(description.getDisplayName());
            }
            return applyToClass(base, description);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: selection check failed, keeping the test", t);
            return base;
        }
    }

    /** As a {@code @ClassRule}: skip the class only when the run selected none of its tests. */
    private Statement applyToClass(Statement base, Description description) {
        Class<?> testClass = description.getTestClass();
        if (testClass == null) {
            return base;
        }
        List<Method> tests = Reflections.annotatedMethods(testClass, Test.class);
        if (tests.isEmpty()) {
            return base;
        }
        for (Method test : tests) {
            if (PlanSelection.allows(TestRefs.fromMethod(testClass, test))) {
                return base;
            }
        }
        return skip(testClass.getName());
    }

    private static Statement skip(final String name) {
        return new Statement() {
            @Override
            public void evaluate() {
                throw new AssumptionViolatedException(
                        name + " is not part of the DoQA run being executed");
            }
        };
    }
}
