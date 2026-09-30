package app.doqa.junit5;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.TestRef;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

/**
 * The single place that builds {@link TestRef}s for the JUnit 5 adapter - discovery filter,
 * plan orderers and the execution listener MUST agree on identities, and the "parameterized"
 * flag (invocations collapse to one method-level id) has a different signal per phase:
 * <ul>
 *   <li>report time ({@link #fromIdentifier}): the uniqueId contains a
 *       {@code test-template-invocation} segment - this exact invocation of a template;</li>
 *   <li>discovery time ({@link #fromDescriptor}): the descriptor IS the template container
 *       (last segment type {@code test-template}) - filtered/ordered as a whole;</li>
 *   <li>class scan ({@link #fromMethod}): the method is {@code @TestTemplate}-annotated
 *       (directly or meta-annotated, e.g. {@code @ParameterizedTest}).</li>
 * </ul>
 */
final class TestRefs {

    static final String FRAMEWORK = "junit5";
    static final String FRAMEWORK_LABEL = "junit-platform";

    static {
        AdapterRuntime.configure(FRAMEWORK, FRAMEWORK_LABEL);
    }

    private TestRefs() {
    }

    /** Report-time ref from an execution {@link TestIdentifier} (+ reflection). */
    static TestRef fromIdentifier(TestIdentifier id) {
        String fqcn = null;
        String methodName = null;
        String paramTypes = null;
        Class<?> testClass = null;
        Method testMethod = null;

        Optional<TestSource> sourceOpt = id.getSource();
        if (sourceOpt.isPresent()) {
            TestSource source = sourceOpt.get();
            if (source instanceof MethodSource) {
                MethodSource ms = (MethodSource) source;
                fqcn = ms.getClassName();
                methodName = ms.getMethodName();
                paramTypes = ms.getMethodParameterTypes();
                testClass = Reflections.loadClass(fqcn);
                testMethod = Reflections.findMethod(testClass, methodName, paramTypes);
            } else if (source instanceof ClassSource) {
                fqcn = ((ClassSource) source).getClassName();
                testClass = Reflections.loadClass(fqcn);
            }
        }

        boolean parameterized = id.getUniqueId() != null
                && id.getUniqueId().contains("test-template-invocation");

        return ref(fqcn, methodName, paramTypes, id.getDisplayName(),
                parameterized, testClass, testMethod);
    }

    /** Discovery-time ref from a post-discovery {@link TestDescriptor} with a method source. */
    static TestRef fromDescriptor(TestDescriptor descriptor, MethodSource ms) {
        Class<?> testClass = Reflections.loadClass(ms.getClassName());
        return ref(
                ms.getClassName(), ms.getMethodName(), ms.getMethodParameterTypes(),
                descriptor.getDisplayName(), isTemplateContainer(descriptor.getUniqueId()),
                testClass,
                Reflections.findMethod(testClass, ms.getMethodName(), ms.getMethodParameterTypes()));
    }

    /**
     * Class-scan ref from a bare {@link Method} (plan orderers). When no engine display name is
     * available, the Jupiter default {@code method(SimpleParamTypes)} is reconstructed for hash
     * stability.
     */
    static TestRef fromMethod(Method method, String displayName) {
        Class<?> testClass = method.getDeclaringClass();
        MethodSource source = MethodSource.from(method);
        String effectiveDisplayName = displayName != null ? displayName : defaultDisplayName(method);
        return ref(
                testClass.getName(), method.getName(), source.getMethodParameterTypes(),
                effectiveDisplayName, isTemplateMethod(method), testClass, method);
    }

    private static TestRef ref(String fqcn, String methodName, String paramTypes,
                               String displayName, boolean parameterized, Class<?> testClass,
                               Method testMethod) {
        return new TestRef.Builder().fqcn(fqcn).methodName(methodName)
                .methodParamTypes(paramTypes).displayName(displayName).parameterized(parameterized)
                .testClass(testClass).testMethod(testMethod)
                .framework(FRAMEWORK, FRAMEWORK_LABEL)
                .build();
    }

    /** Last uniqueId segment is a {@code @TestTemplate} container ({@code @ParameterizedTest}...). */
    static boolean isTemplateContainer(UniqueId uniqueId) {
        return uniqueId != null && "test-template".equals(uniqueId.getLastSegment().getType());
    }

    /** Last uniqueId segment is a {@code @TestFactory} container (dynamic tests). */
    static boolean isFactoryContainer(UniqueId uniqueId) {
        return uniqueId != null && "test-factory".equals(uniqueId.getLastSegment().getType());
    }

    /** Jupiter's default display name: {@code methodName(SimpleType, SimpleType)}. */
    private static String defaultDisplayName(Method method) {
        DisplayName annotated = method.getAnnotation(DisplayName.class);
        if (annotated != null && !annotated.value().trim().isEmpty()) {
            return annotated.value().trim();
        }
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    /** {@code @TestTemplate}-based (e.g. {@code @ParameterizedTest}) - parameterized signature. */
    private static boolean isTemplateMethod(Method method) {
        if (method.isAnnotationPresent(TestTemplate.class)) {
            return true;
        }
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(TestTemplate.class)) {
                return true;
            }
        }
        return false;
    }
}
