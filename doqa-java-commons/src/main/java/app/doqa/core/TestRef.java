package app.doqa.core;

import java.lang.reflect.Method;

/**
 * Immutable, framework-light description of a single test resolved from the framework's test
 * identifier (+ reflection). Kept free of framework types so {@link Attribution} /
 * {@link SignatureHash} stay unit-testable without launching an engine.
 *
 * <p>{@code parameterized} means "invocations of this element collapse to one method-level
 * identity" (a parameterized/templated test) - each framework adapter derives it from its own
 * phase-specific signal; keep those derivations in ONE factory per adapter so discovery,
 * ordering and reporting agree on identities.
 *
 * <p>Internal adapter API.
 */
public final class TestRef {

    public final String fqcn;
    public final String methodName;
    public final String methodParamTypes;
    public final String displayName;
    public final boolean parameterized;
    public final Class<?> testClass;   // nullable
    public final Method testMethod;    // nullable

    public final String explicitId;
    public final String titleId;
    public final String allureId;
    private final long[] caseIds;
    public final String signature;
    public final String namespace;
    public final String classname;
    private final String framework;
    private final String frameworkLabel;
    private final String methodKey;

    public TestRef(String fqcn, String methodName, String methodParamTypes, String displayName,
                   boolean parameterized, Class<?> testClass, Method testMethod) {
        this(new Builder().fqcn(fqcn).methodName(methodName).methodParamTypes(methodParamTypes)
                .displayName(displayName).parameterized(parameterized)
                .testClass(testClass).testMethod(testMethod));
    }

    private TestRef(Builder b) {
        this.fqcn = b.fqcn;
        this.methodName = b.methodName;
        this.methodParamTypes = b.methodParamTypes;
        this.displayName = b.displayName;
        this.parameterized = b.parameterized;
        this.testClass = b.testClass;
        this.testMethod = b.testMethod;
        this.explicitId = b.explicitId;
        this.titleId = b.titleId;
        this.allureId = b.allureId;
        this.caseIds = b.caseIds == null || b.caseIds.length == 0 ? null : b.caseIds.clone();
        this.signature = b.signature;
        this.namespace = b.namespace;
        this.classname = b.classname;
        this.framework = b.framework;
        this.frameworkLabel = b.frameworkLabel;
        this.methodKey = b.methodKey;
    }

    /** {@code <fqcn>.<method>} - the default autotest name path. */
    public String fullName() {
        if (fqcn == null || fqcn.isEmpty()) {
            return methodName == null ? displayName : methodName;
        }
        if (methodName == null || methodName.isEmpty()) {
            return fqcn;
        }
        return fqcn + "." + methodName;
    }

    /** {@code <fqcn>#<method>} - identity of the declaring method (duplicate-id detection). */
    public String methodKey() {
        return methodKey != null ? methodKey : fqcn + "#" + methodName;
    }

    /** Package part of the FQCN (default namespace), or null. */
    public String packageName() {
        if (fqcn == null) {
            return null;
        }
        int dot = fqcn.lastIndexOf('.');
        return dot > 0 ? fqcn.substring(0, dot) : null;
    }

    /** Simple class name (default classname), or null. */
    public String simpleClassName() {
        if (fqcn == null) {
            return null;
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    public long[] caseIds() {
        return caseIds == null ? null : caseIds.clone();
    }

    public String framework() {
        return framework != null && !framework.isEmpty() ? framework : AdapterRuntime.framework();
    }

    public String frameworkLabel() {
        return frameworkLabel != null && !frameworkLabel.isEmpty()
                ? frameworkLabel : AdapterRuntime.frameworkLabel();
    }

    /** Builder for refs that carry more than a Java method does. */
    public static final class Builder {
        private String fqcn;
        private String methodName;
        private String methodParamTypes;
        private String displayName;
        private boolean parameterized;
        private Class<?> testClass;
        private Method testMethod;
        private String explicitId;
        private String titleId;
        private String allureId;
        private long[] caseIds;
        private String signature;
        private String namespace;
        private String classname;
        private String framework;
        private String frameworkLabel;
        private String methodKey;

        public Builder fqcn(String v) { this.fqcn = v; return this; }
        public Builder methodName(String v) { this.methodName = v; return this; }
        public Builder methodParamTypes(String v) { this.methodParamTypes = v; return this; }
        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder parameterized(boolean v) { this.parameterized = v; return this; }
        public Builder testClass(Class<?> v) { this.testClass = v; return this; }
        public Builder testMethod(Method v) { this.testMethod = v; return this; }
        public Builder explicitId(String v) { this.explicitId = v; return this; }
        public Builder titleId(String v) { this.titleId = v; return this; }
        public Builder allureId(String v) { this.allureId = v; return this; }
        public Builder caseIds(long... v) { this.caseIds = v; return this; }
        public Builder signature(String v) { this.signature = v; return this; }
        public Builder namespace(String v) { this.namespace = v; return this; }
        public Builder classname(String v) { this.classname = v; return this; }
        public Builder framework(String id, String label) {
            this.framework = id;
            this.frameworkLabel = label;
            return this;
        }
        public Builder methodKey(String v) { this.methodKey = v; return this; }

        public TestRef build() {
            return new TestRef(this);
        }
    }
}
