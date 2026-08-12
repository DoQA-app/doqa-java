package app.doqa.junit4;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.Limits;
import app.doqa.core.Placeholders;
import app.doqa.core.RuntimeContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.Parameterized;
import org.junit.runners.model.FrameworkField;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;
import org.junit.runners.parameterized.BlockJUnit4ClassRunnerWithParameters;
import org.junit.runners.parameterized.TestWithParameters;

/**
 * The parameterized counterpart of {@link DoqaRunner}, built by
 * {@link DoqaParametersRunnerFactory}. Beyond the phases and selection its sibling provides, it
 * reports the invocation's <em>actual</em> arguments as named parameters, which is also what makes
 * {@code {param}} placeholders in {@code @DoqaId} / {@code @DoqaTitle} / labels resolve for a
 * JUnit 4 parameterized test.
 *
 * <p>Class fixtures are deliberately left to the run listener: {@code Parameterized} runs
 * {@code @BeforeClass} / {@code @AfterClass} once around all parameter sets, in the enclosing suite
 * runner, so this runner never sees them.
 */
public class DoqaRunnerWithParameters extends BlockJUnit4ClassRunnerWithParameters {

    static {
        AdapterRuntime.configure("junit4", "junit4");
    }

    private static final Logger LOG = Logger.getLogger(DoqaRunnerWithParameters.class.getName());

    private final List<Object> parameterValues;
    private final List<String> parameterNames;

    public DoqaRunnerWithParameters(TestWithParameters test) throws InitializationError {
        super(test);
        // the base runner keeps the parameter values private, so capture them while they are in hand
        this.parameterValues = new ArrayList<Object>(test.getParameters());
        this.parameterNames = resolveNames(test.getTestClass(), parameterValues.size());
    }

    @Override
    public void run(RunNotifier notifier) {
        RunnerSupport.registerListener(notifier);
        super.run(notifier);
    }

    /** Mode-0 deselection; a whole invocation of a parameterized method is kept or dropped. */
    @Override
    protected boolean isIgnored(FrameworkMethod child) {
        if (RunnerSupport.deselected(describeChild(child))) {
            return true;
        }
        return super.isIgnored(child);
    }

    @Override
    protected List<FrameworkMethod> computeTestMethods() {
        List<FrameworkMethod> methods = super.computeTestMethods();
        // see RunnerSupport.Refs: identity from the method, not from describeChild
        return RunnerSupport.planOrder(getTestClass(), methods,
                method -> TestRefs.fromMethod(getTestClass().getJavaClass(), method.getMethod()));
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        final Statement base = super.methodInvoker(method, test);
        final Description description = describeChild(method);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                RuntimeContext ctx = RunnerSupport.bind(description, RuntimeContext.Phase.CALL);
                applyParameters(ctx);
                base.evaluate();
            }
        };
    }

    @Override
    protected Statement withBefores(FrameworkMethod method, Object target, Statement statement) {
        try {
            return RunnerSupport.fixtureSteps(statement,
                    getTestClass().getAnnotatedMethods(Before.class), target,
                    describeChild(method), true);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: @Before methods stay unreported", t);
            return super.withBefores(method, target, statement);
        }
    }

    @Override
    protected Statement withAfters(FrameworkMethod method, Object target, Statement statement) {
        try {
            return RunnerSupport.fixtureSteps(statement,
                    getTestClass().getAnnotatedMethods(After.class), target,
                    describeChild(method), false);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: @After methods stay unreported", t);
            return super.withAfters(method, target, statement);
        }
    }

    /** Replaces the invocation name the listener parsed with the real argument values. */
    private void applyParameters(RuntimeContext ctx) {
        if (ctx == null || parameterValues.isEmpty()) {
            return;
        }
        try {
            ctx.invocationParameters.clear();
            for (int i = 0; i < parameterValues.size(); i++) {
                String value = Limits.truncate(Placeholders.stringify(parameterValues.get(i)),
                        Limits.maxParameterLength());
                ctx.invocationParameters.add(new Object[]{parameterNames.get(i), value});
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: invocation parameters stay unreported", t);
        }
    }

    /**
     * Names for the parameter slots: {@code @Parameter} field names first (field injection), then
     * the names of the single constructor's parameters (constructor injection - present only when
     * the host compiles with {@code -parameters}), then {@code argN}.
     */
    private static List<String> resolveNames(TestClass testClass, int count) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            names.add(null);
        }
        try {
            for (FrameworkField field : testClass.getAnnotatedFields(Parameterized.Parameter.class)) {
                int index = field.getField().getAnnotation(Parameterized.Parameter.class).value();
                if (index >= 0 && index < count) {
                    names.set(index, field.getField().getName());
                }
            }
            Constructor<?>[] constructors = testClass.getJavaClass().getConstructors();
            if (constructors.length == 1) {
                Parameter[] declared = constructors[0].getParameters();
                for (int i = 0; i < count && i < declared.length; i++) {
                    if (names.get(i) == null && declared[i].isNamePresent()) {
                        names.set(i, declared[i].getName());
                    }
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA junit4: parameter names fall back to argN", t);
        }
        for (int i = 0; i < count; i++) {
            if (names.get(i) == null) {
                names.set(i, "arg" + i);
            }
        }
        return names;
    }
}
