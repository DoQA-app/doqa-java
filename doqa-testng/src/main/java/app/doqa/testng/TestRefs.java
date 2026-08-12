package app.doqa.testng;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.Limits;
import app.doqa.core.Placeholders;
import app.doqa.core.SignatureHash;
import app.doqa.core.TestRef;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

/**
 * The single place that builds {@link TestRef}s and invocation keys for the TestNG adapter - the
 * listener and the method interceptor MUST agree on identities, or a test would be deselected under
 * one externalId and reported under another.
 *
 * <p>TestNG runs the same {@code @Test} method many times by design ({@code @DataProvider},
 * {@code invocationCount}, retries, {@code @Factory} instances, several {@code <test>} blocks), so:
 * <ul>
 *   <li>the context key is a UUID stored on the {@link ITestResult} itself - the only value that is
 *       unique per invocation and available from every callback of that invocation;</li>
 *   <li>{@code parameterized} means "invocations collapse to one identity" and is derived from the
 *       method, not from a single invocation, so the interceptor (which sees no results yet) and the
 *       listener resolve the same externalId;</li>
 *   <li>the display name deliberately ignores {@code ITestResult.getName()} / {@code ITest}, which
 *       are per-invocation and would make the signature hash unstable.</li>
 * </ul>
 *
 * <p>Internal adapter API.
 */
final class TestRefs {

    static {
        AdapterRuntime.configure("testng", "testng");
    }

    /** Attribute holding this invocation's key; {@link ITestResult} is an {@code IAttributes}. */
    private static final String KEY_ATTRIBUTE = "doqa.invocationKey";

    private TestRefs() {
    }

    /**
     * Stable key of one invocation, created on first use and kept on the result. TestNG's own
     * {@code id()} would do, but it only exists since 7.5 and the adapter supports 7.4.
     */
    static synchronized String key(ITestResult result) {
        if (result == null) {
            return null;
        }
        Object existing = result.getAttribute(KEY_ATTRIBUTE);
        if (existing instanceof String) {
            return (String) existing;
        }
        String key = UUID.randomUUID().toString();
        result.setAttribute(KEY_ATTRIBUTE, key);
        return key;
    }

    /** Report-time ref for one invocation. */
    static TestRef fromResult(ITestResult result) {
        ITestNGMethod method = result.getMethod();
        // the executed class, not the declaring one: an inherited test belongs to the subclass
        Class<?> testClass = result.getTestClass() != null
                ? result.getTestClass().getRealClass()
                : (method == null ? null : method.getRealClass());
        return build(testClass, method);
    }

    /** Discovery-time ref from a bare {@link ITestNGMethod}, where no {@link ITestResult} exists yet. */
    static TestRef fromMethod(ITestNGMethod method) {
        Class<?> testClass = method.getTestClass() != null
                ? method.getTestClass().getRealClass()
                : method.getRealClass();
        return build(testClass, method);
    }

    private static TestRef build(Class<?> testClass, ITestNGMethod method) {
        Method javaMethod = method == null || method.getConstructorOrMethod() == null
                ? null
                : method.getConstructorOrMethod().getMethod();
        String fqcn = testClass != null ? testClass.getName()
                : (javaMethod == null ? null : javaMethod.getDeclaringClass().getName());
        String methodName = javaMethod != null ? javaMethod.getName()
                : (method == null ? null : method.getMethodName());
        return new TestRef(fqcn, methodName, SignatureHash.parameterTypes(javaMethod),
                displayName(method, methodName), parameterized(method, javaMethod),
                testClass, javaMethod);
    }

    /**
     * {@code @Test(description)} when set, else the plain method name - free of per-invocation values
     * so that the signature-hash fallback id is stable across invocations.
     */
    private static String displayName(ITestNGMethod method, String methodName) {
        if (method != null) {
            String description = method.getDescription();
            if (description != null && !description.trim().isEmpty()) {
                return description.trim();
            }
        }
        return methodName;
    }

    /**
     * True when several invocations of this method share one identity: it takes arguments, repeats,
     * or comes from a factory. Read off the method, so discovery and report time agree.
     */
    private static boolean parameterized(ITestNGMethod method, Method javaMethod) {
        if (javaMethod != null && javaMethod.getParameterCount() > 0) {
            return true;
        }
        if (method == null) {
            return false;
        }
        return method.getInvocationCount() > 1
                || method.getDataProviderMethod() != null
                || method.getFactoryMethodParamsInfo() != null;
    }

    /**
     * Named parameters of this invocation. Names come from the method signature (real ones when the
     * host compiles with {@code -parameters}, else {@code argN}); values TestNG injects itself
     * ({@code ITestContext}, {@code Method}, {@code XmlTest}, ...) carry no user data and are skipped.
     */
    static List<Object[]> invocationParameters(ITestResult result) {
        List<Object[]> parameters = new ArrayList<>();
        Object[] values = result.getParameters();
        if (values == null || values.length == 0) {
            return parameters;
        }
        Method javaMethod = result.getMethod() == null
                || result.getMethod().getConstructorOrMethod() == null
                ? null
                : result.getMethod().getConstructorOrMethod().getMethod();
        Parameter[] declared = javaMethod == null ? new Parameter[0] : javaMethod.getParameters();
        for (int i = 0; i < values.length; i++) {
            Parameter parameter = i < declared.length ? declared[i] : null;
            if (isInjected(parameter, values[i])) {
                continue;
            }
            String name = parameter != null ? parameter.getName() : "arg" + i;
            parameters.add(new Object[]{name,
                    Limits.truncate(Placeholders.stringify(values[i]), Limits.maxParameterLength())});
        }
        return parameters;
    }

    /** TestNG-injected argument: matched by declared type, or by value when the type is generic. */
    private static boolean isInjected(Parameter parameter, Object value) {
        if (parameter != null && isFrameworkType(parameter.getType())) {
            return true;
        }
        return parameter == null && value != null && isFrameworkType(value.getClass());
    }

    private static boolean isFrameworkType(Class<?> type) {
        return type.getName().startsWith("org.testng.")
                || java.lang.reflect.Method.class.isAssignableFrom(type);
    }

    /** Native TestNG {@code groups} join the DoQA tags - one tagging, both mechanics see it. */
    static List<String> groups(ITestNGMethod method) {
        List<String> tags = new ArrayList<>();
        if (method == null || method.getGroups() == null) {
            return tags;
        }
        for (String group : method.getGroups()) {
            if (group != null && !group.trim().isEmpty()) {
                tags.add(group.trim());
            }
        }
        return tags;
    }
}
