package app.doqa.junit5;

import app.doqa.Doqa;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.ClassFixtures;
import app.doqa.core.DoqaContexts;
import app.doqa.core.Limits;
import app.doqa.core.Outcomes;
import app.doqa.core.Placeholders;
import app.doqa.core.PlanSelection;
import app.doqa.core.RuntimeContext;
import app.doqa.core.StepNode;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.opentest4j.TestAbortedException;

/**
 * JUnit Jupiter extension that captures fixtures and invocation parameters:
 * <ul>
 *   <li>{@code @BeforeEach} / {@code @AfterEach}: per-test setup / teardown step results
 *       (phase flips so AspectJ {@code @Step} / {@code Doqa.step} bucket correctly);</li>
 *   <li>{@code @BeforeAll} / {@code @AfterAll}: class-level fixtures recorded into
 *       {@link ClassFixtures} and attached by the session. A transient context is bound for the
 *       duration, so {@code Doqa.step} / {@code @Step} / {@code Doqa.addAttachments} inside a
 *       class fixture nest under the fixture node instead of being dropped;</li>
 *   <li>{@code @ParameterizedTest} arguments: named parameters (real names when the host
 *       compiles with {@code -parameters}, else {@code arg0..argN}), also feeding
 *       {@code {param}} placeholder substitution. JUnit-injected parameters (TestInfo,
 *       ArgumentsAccessor, {@code @TempDir}, ...) are skipped. In a selective (mode-0) run an
 *       invocation outside the run is aborted here, before its body executes.</li>
 * </ul>
 * Runs on the test thread via {@link InvocationInterceptor}.
 *
 * <p>Registration: {@code @ExtendWith(DoqaExtension.class)} on the test class, or global
 * auto-detection ({@code junit.jupiter.extensions.autodetection.enabled=true} +
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension}). Without it, fixtures are
 * not captured and all steps land in the call bucket (documented fallback).
 */
public class DoqaExtension implements InvocationInterceptor {

    static {
        AdapterRuntime.configure("junit5", "junit-platform");
        AdapterRuntime.configureSkipSignal(TestAbortedException::new);
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        runAsClassFixture(invocation, invocationContext, true);
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation,
                                        ReflectiveInvocationContext<Method> invocationContext,
                                        ExtensionContext extensionContext) throws Throwable {
        runAsClassFixture(invocation, invocationContext, false);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> invocationContext,
                                          ExtensionContext extensionContext) throws Throwable {
        RuntimeContext ctx = bind(extensionContext);
        if (ctx == null) {
            invocation.proceed();
            return;
        }
        ctx.phase = RuntimeContext.Phase.SETUP;
        runAsStep(invocation, invocationContext.getExecutable().getName());
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        RuntimeContext ctx = bind(extensionContext);
        if (ctx == null) {
            invocation.proceed();
            return;
        }
        ctx.phase = RuntimeContext.Phase.TEARDOWN;
        runAsStep(invocation, invocationContext.getExecutable().getName());
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        RuntimeContext ctx = bind(extensionContext);
        if (ctx != null) {
            ctx.phase = RuntimeContext.Phase.CALL;
        }
        invocation.proceed();
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        RuntimeContext ctx = bind(extensionContext);
        if (ctx != null) {
            ctx.phase = RuntimeContext.Phase.CALL;
            captureInvocationParameters(ctx, invocationContext);
            // arguments exist only now: the earliest a {param} id can be compared with the run
            RuntimeException deselected = PlanSelection.deselectedInvocationSignal(ctx);
            if (deselected != null) {
                invocation.skip();
                throw deselected;
            }
        }
        invocation.proceed();
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation,
                                     DynamicTestInvocationContext invocationContext,
                                     ExtensionContext extensionContext) throws Throwable {
        // @TestFactory: the listener opens the context on the dynamic test's executionStarted,
        // which fires before this interceptor; bind it so body steps land on the right test.
        // (@BeforeEach fixtures around a factory run before any dynamic test exists, so they
        // cannot be attributed to a single invocation.)
        RuntimeContext ctx = bind(extensionContext);
        if (ctx != null) {
            ctx.phase = RuntimeContext.Phase.CALL;
        }
        invocation.proceed();
    }

    /** Named per-argument parameters of a @ParameterizedTest invocation. */
    private static void captureInvocationParameters(RuntimeContext ctx,
                                                    ReflectiveInvocationContext<Method> ic) {
        Parameter[] declared = ic.getExecutable().getParameters();
        List<Object> args = ic.getArguments();
        for (int i = 0; i < declared.length && i < args.size(); i++) {
            if (isJunitInjected(declared[i])) {
                continue;
            }
            ctx.invocationParameters.add(new Object[]{
                    declared[i].getName(),
                    Limits.truncate(Placeholders.stringify(args.get(i)), Limits.maxParameterLength())});
        }
    }

    /**
     * JUnit-injected parameters carry no user data: TestInfo/TestReporter/RepetitionInfo,
     * ArgumentsAccessor (org.junit.jupiter.params.aggregator) - matched by type prefix - and
     * {@code @TempDir} paths (a fresh random value per run), matched by annotation.
     */
    private static boolean isJunitInjected(Parameter parameter) {
        String type = parameter.getType().getName();
        if (type.startsWith("org.junit.")) {
            return true;
        }
        for (Annotation annotation : parameter.getAnnotations()) {
            if (annotation.annotationType().getName().startsWith("org.junit.")) {
                return true;
            }
        }
        return false;
    }

    private void runAsStep(Invocation<Void> invocation, String title) throws Throwable {
        Doqa.step(title, () -> {
            invocation.proceed();
            return null;
        });
    }

    /**
     * Times a @BeforeAll/@AfterAll invocation and records it into {@link ClassFixtures}. A
     * transient context with the fixture node as the open step is bound to the thread for the
     * duration: nested {@code Doqa.step}/{@code @Step} become children of the fixture node and
     * {@code Doqa.addAttachments}/{@code addMessage} land on it instead of vanishing.
     */
    private static void runAsClassFixture(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> ic,
                                          boolean before) throws Throwable {
        // Record under the exact declaring class so a nested class's @BeforeAll is not attributed
        // to the enclosing class's tests; ClassFixtures walks the enclosing chain at read time.
        Class<?> declaring = ic.getTargetClass() != null
                ? ic.getTargetClass()
                : ic.getExecutable().getDeclaringClass();
        String classKey = declaring == null ? null : declaring.getName();
        StepNode node = new StepNode(ic.getExecutable().getName());
        RuntimeContext fixtureCtx = new RuntimeContext(null);
        fixtureCtx.stepStack.push(node);
        RuntimeContext previous = DoqaContexts.push(fixtureCtx);
        try {
            invocation.proceed();
            node.outcome = "passed";
        } catch (Throwable t) {
            node.outcome = Outcomes.failureOutcome(t);
            node.message = node.message == null
                    ? Outcomes.messageOf(t)
                    : node.message + "\n" + Outcomes.messageOf(t);
            throw t;
        } finally {
            DoqaContexts.restore(previous);
            node.durationMs = System.currentTimeMillis() - node.startMillis;
            if (before) {
                ClassFixtures.recordBefore(classKey, node);
            } else {
                ClassFixtures.recordAfter(classKey, node);
            }
        }
    }

    /**
     * Re-bind the thread-local to this test's context (created by the listener) and return it, or
     * {@code null} when the listener has not opened one. There is no fallback to the current
     * thread-local: under parallel execution that would attribute this test's fixture steps to
     * whichever test happens to own the thread.
     */
    private RuntimeContext bind(ExtensionContext ec) {
        return DoqaContexts.bind(ec.getUniqueId());
    }
}
