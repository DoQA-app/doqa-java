package app.doqa.junit4;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared reflective helpers to resolve a test class / method / fixtures from the coordinates a
 * JUnit 4 {@code Description} carries (class name + method name only - there are no parameter
 * types). Lookups are cached (the same method resolves on every event and per parameterized
 * invocation) and NEVER throw: broken classpaths ({@code NoClassDefFoundError} from a method
 * signature referencing an absent class) degrade to {@code null} / an empty list - reporting
 * machinery must not break the user's run.
 */
final class Reflections {

    private static final ConcurrentMap<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    private Reflections() {
    }

    static Class<?> loadClass(String fqcn) {
        if (fqcn == null) {
            return null;
        }
        return CLASSES.computeIfAbsent(fqcn, key -> {
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = Reflections.class.getClassLoader();
                }
                return Optional.of(Class.forName(key, false, cl));
            } catch (Throwable t) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    static Method findMethod(Class<?> clazz, String methodName) {
        if (clazz == null || methodName == null || methodName.isEmpty()) {
            return null;
        }
        String key = clazz.getName() + "#" + methodName;
        return METHODS.computeIfAbsent(key, k -> Optional.ofNullable(resolveMethod(clazz, methodName)))
                .orElse(null);
    }

    /**
     * A JUnit 4 test method takes no arguments ({@code Parameterized} injects through the
     * constructor or fields), so the no-arg candidate closest to {@code clazz} wins - that also
     * resolves overrides correctly. {@code @Theory} methods do take arguments: fall back to the
     * first candidate by name.
     */
    private static Method resolveMethod(Class<?> clazz, String methodName) {
        Method byName = null;
        try {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : declaredMethods(c)) {
                    if (m.isSynthetic() || !m.getName().equals(methodName)) {
                        continue;
                    }
                    if (m.getParameterCount() == 0) {
                        return m;
                    }
                    if (byName == null) {
                        byName = m;
                    }
                }
            }
        } catch (Throwable t) {
            // e.g. NoClassDefFoundError from another method's signature
            return byName;
        }
        return byName;
    }

    /** Methods of the class hierarchy carrying {@code annotation}, most-derived class first. */
    static List<Method> annotatedMethods(Class<?> clazz, Class<? extends Annotation> annotation) {
        List<Method> found = new ArrayList<>();
        if (clazz == null) {
            return found;
        }
        try {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : declaredMethods(c)) {
                    if (!m.isSynthetic() && m.isAnnotationPresent(annotation)) {
                        found.add(m);
                    }
                }
            }
        } catch (Throwable t) {
            // broken classpath while introspecting: keep whatever was collected
        }
        return found;
    }

    static boolean hasAnnotatedMethod(Class<?> clazz, Class<? extends Annotation> annotation) {
        return !annotatedMethods(clazz, annotation).isEmpty();
    }

    private static Method[] declaredMethods(Class<?> c) {
        try {
            return c.getDeclaredMethods();
        } catch (Throwable t) {
            // NoClassDefFoundError from a method signature referencing an absent class
            return new Method[0];
        }
    }

    /** Clear caches at run boundaries - long-lived JVMs may reload test classes. */
    static void reset() {
        CLASSES.clear();
        METHODS.clear();
    }
}
