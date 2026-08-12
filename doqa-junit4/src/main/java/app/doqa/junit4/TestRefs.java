package app.doqa.junit4;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.Limits;
import app.doqa.core.SignatureHash;
import app.doqa.core.TestRef;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.experimental.categories.Category;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * The single place that builds {@link TestRef}s for the JUnit 4 adapter - the run listener, the
 * optional runner and the select rule MUST agree on identities, or a test would be selected under
 * one externalId and reported under another.
 *
 * <p>JUnit 4 hands out far less than the JUnit Platform: a {@code Description} carries the class
 * name and a method name with the parameterized suffix baked in
 * ({@code alwaysPasses[0: value=alpha]}), and no parameter types at all. Hence:
 * <ul>
 *   <li>the {@code [...]} suffix is stripped for the method name (it feeds {@code runner_method}
 *       and the signature hash - keeping it would give every invocation its own autotest);</li>
 *   <li>{@code parameterized} is "the suffix was there", which collapses all invocations of one
 *       method onto a single identity, exactly like a Jupiter {@code @ParameterizedTest};</li>
 *   <li>parameter types are reconstructed from the resolved {@link Method}.</li>
 * </ul>
 *
 * <p>Internal adapter API.
 */
final class TestRefs {

    static {
        AdapterRuntime.configure("junit4", "junit4");
    }

    /** {@code "0: value=alpha"} - the default {@code @Parameters} name is the index alone. */
    private static final Pattern INDEXED_SUFFIX = Pattern.compile("^\\d+(?::\\s*(.*))?$");

    private TestRefs() {
    }

    /**
     * Registry key for {@code DoqaContexts}. {@code Description} has no public unique id, but its
     * display name backs its {@code equals} and is stable between {@code testStarted} and
     * {@code testFinished}; parameterized invocations differ by the index in the name.
     */
    static String key(Description description) {
        return description == null ? null : description.getDisplayName();
    }

    /** Report-time ref from an execution {@link Description} (+ reflection). */
    static TestRef fromDescription(Description description) {
        String fqcn = description.getClassName();
        Class<?> testClass = description.getTestClass() != null
                ? description.getTestClass()
                : Reflections.loadClass(fqcn);
        if (testClass != null) {
            // a Parameterized sub-suite reports the invocation name as its class name
            fqcn = testClass.getName();
        }
        String rawMethod = description.getMethodName();
        if (rawMethod == null) {
            // class-level description (@Ignore on the class, a failing @BeforeClass)
            return new TestRef(fqcn, null, null, simpleName(fqcn), false, testClass, null);
        }
        String methodName = cleanMethodName(rawMethod);
        Method method = Reflections.findMethod(testClass, methodName);
        return new TestRef(fqcn, methodName, SignatureHash.parameterTypes(method), rawMethod,
                invocationSuffix(rawMethod) != null, testClass, method);
    }

    /**
     * Ref for a test that never produced its own event - the descendants of an {@code @Ignore}d
     * class, which JUnit reports as a single childless description. A {@code Parameterized} class is
     * marked parameterized so the synthesized identity matches a real invocation's.
     */
    static TestRef fromMethod(Class<?> testClass, Method method) {
        return new TestRef(testClass.getName(), method.getName(),
                SignatureHash.parameterTypes(method), method.getName(), isParameterized(testClass),
                testClass, method);
    }

    /** {@code alwaysPasses[0: value=alpha]} &rarr; {@code alwaysPasses}. */
    static String cleanMethodName(String rawMethodName) {
        if (rawMethodName == null) {
            return null;
        }
        int bracket = rawMethodName.indexOf('[');
        return bracket > 0 && rawMethodName.endsWith("]")
                ? rawMethodName.substring(0, bracket)
                : rawMethodName;
    }

    /** Content of the invocation suffix ({@code 0: value=alpha}), or null when there is none. */
    static String invocationSuffix(String rawMethodName) {
        if (rawMethodName == null || !rawMethodName.endsWith("]")) {
            return null;
        }
        int bracket = rawMethodName.indexOf('[');
        return bracket > 0 ? rawMethodName.substring(bracket + 1, rawMethodName.length() - 1) : null;
    }

    /**
     * Invocation parameters recovered from the suffix: a bare index carries no information and
     * yields nothing, while {@code "0: value=alpha"} yields one {@code arguments} parameter - the
     * same shape the shared core derives from a Jupiter invocation display name.
     */
    static List<Object[]> invocationParameters(String rawMethodName) {
        List<Object[]> parameters = new ArrayList<>();
        String suffix = invocationSuffix(rawMethodName);
        if (suffix == null || suffix.trim().isEmpty()) {
            return parameters;
        }
        String value = suffix.trim();
        Matcher indexed = INDEXED_SUFFIX.matcher(value);
        if (indexed.matches()) {
            String rest = indexed.group(1);
            if (rest == null || rest.trim().isEmpty()) {
                return parameters;
            }
            value = rest.trim();
        }
        parameters.add(new Object[]{"arguments", Limits.truncate(value, Limits.maxParameterLength())});
        return parameters;
    }

    /** Simple names of the {@code @Category} classes of the method and its declaring class. */
    static List<String> categories(Class<?> testClass, Method method) {
        List<String> names = new ArrayList<>();
        collectCategories(names, testClass);
        collectCategories(names, method);
        return names;
    }

    private static void collectCategories(List<String> names, java.lang.reflect.AnnotatedElement element) {
        if (element == null) {
            return;
        }
        Category category = element.getAnnotation(Category.class);
        if (category == null) {
            return;
        }
        for (Class<?> type : category.value()) {
            if (type != null) {
                names.add(type.getSimpleName());
            }
        }
    }

    /** True when the class runs with {@code Parameterized} (invocations share one identity). */
    static boolean isParameterized(Class<?> testClass) {
        if (testClass == null) {
            return false;
        }
        RunWith runWith = testClass.getAnnotation(RunWith.class);
        return runWith != null && Parameterized.class.isAssignableFrom(runWith.value());
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) {
            return null;
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
