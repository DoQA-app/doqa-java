package app.doqa.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.Attribution;
import app.doqa.core.TestRef;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.testng.IClass;
import org.testng.IDataProviderMethod;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.internal.ConstructorOrMethod;
import org.testng.xml.XmlTest;

/**
 * Unit fixtures for {@link TestRefs} - the single factory every entry point of the adapter resolves
 * identities through. What is pinned here is the TestNG specifics the shared core cannot know about:
 * the per-invocation context key kept on the {@link ITestResult}, the fact that a ref built from a
 * bare {@link ITestNGMethod} (deselection, ordering) and one built from a result (reporting) resolve
 * to the SAME externalId, and the projection of TestNG metadata (description, groups, argument
 * values) onto the ref.
 *
 * <p>The framework objects are dynamic-proxy stubs answering only what the factory reads - the real
 * implementations live in {@code org.testng.internal} and cannot be built outside a run.
 */
public class TestRefsTest {

    /** Reflection target: not a runnable test class (no TestNG run is ever built for it). */
    public static class Sample {

        public void alwaysPasses() {
        }

        public void withArgs(String browser) {
        }

        public void injected(ITestContext context, String browser, Method method, XmlTest xmlTest) {
        }
    }

    @BeforeClass
    public void configureRuntime() {
        AdapterRuntime.configure("testng", "testng");
    }

    // ------------------------------------------------------------------ invocation key
    @Test
    public void keyIsStableWithinOneResultAndUniqueAcrossResults() {
        ITestNGMethod method = method("alwaysPasses");
        ITestResult first = result(method);
        String key = TestRefs.key(first);
        assertNotNull(key);
        assertEquals(TestRefs.key(first), key, "every callback of one invocation sees one key");
        assertNotEquals(TestRefs.key(result(method)), key,
                "a second invocation of the same method gets its own key");
        assertNull(TestRefs.key(null));
    }

    // ------------------------------------------------------------------ identity
    @Test
    public void refFromResultAndFromMethodResolveTheSameExternalId() {
        // the invariant the adapter lives on: the interceptor deselects by the ref of a bare
        // ITestNGMethod, the listener reports by the ref of an ITestResult.
        ITestNGMethod method = method("alwaysPasses");
        TestRef fromMethod = TestRefs.fromMethod(method);
        TestRef fromResult = TestRefs.fromResult(result(method));

        assertEquals(fromResult.fqcn, Sample.class.getName());
        assertEquals(fromResult.fqcn, fromMethod.fqcn);
        assertEquals(fromResult.methodName, fromMethod.methodName);
        assertEquals(fromResult.methodParamTypes, fromMethod.methodParamTypes);
        assertEquals(fromResult.displayName, fromMethod.displayName);
        assertEquals(fromResult.parameterized, fromMethod.parameterized);
        assertEquals(fromResult.testClass, Sample.class);
        assertNotNull(fromResult.testMethod);
        assertEquals(Attribution.resolve(fromResult).externalId,
                Attribution.resolve(fromMethod).externalId);
    }

    @Test
    public void fallbackExternalIdCarriesTheAdapterPrefix() {
        String externalId = Attribution.resolve(TestRefs.fromMethod(method("alwaysPasses"))).externalId;
        assertTrue(externalId.startsWith("testng:"), "adapter-prefixed fallback id: " + externalId);
    }

    @Test
    public void everyInvocationOfAParameterizedMethodCollapsesOntoOneId() {
        // args travel in parameters[]; the identity must not split per invocation
        ITestNGMethod method = method("withArgs", String.class);
        TestRef ref = TestRefs.fromMethod(method);
        assertTrue(ref.parameterized);
        assertEquals(ref.methodParamTypes, "java.lang.String");
        assertEquals(Attribution.resolve(TestRefs.fromResult(result(method, "chrome"))).externalId,
                Attribution.resolve(TestRefs.fromResult(result(method, "firefox"))).externalId);
    }

    // ------------------------------------------------------------------ parameterized flag
    @Test
    public void parameterizedIsDerivedFromTheMethodNotFromOneInvocation() {
        assertFalse(TestRefs.fromMethod(method("alwaysPasses")).parameterized,
                "a plain method is not parameterized");
        assertTrue(TestRefs.fromMethod(method("withArgs", String.class)).parameterized,
                "arguments (data provider / xml parameters)");
        assertTrue(TestRefs.fromMethod(
                        methodStub("alwaysPasses").answer("getInvocationCount", 2)
                                .as(ITestNGMethod.class)).parameterized,
                "invocationCount > 1");
        assertTrue(TestRefs.fromMethod(
                        methodStub("alwaysPasses")
                                .answer("getDataProviderMethod", new Stub().as(IDataProviderMethod.class))
                                .as(ITestNGMethod.class)).parameterized,
                "@DataProvider");
    }

    // ------------------------------------------------------------------ display name + groups
    @Test
    public void displayNameIsTheDescriptionElseTheMethodName() {
        assertEquals(TestRefs.fromMethod(
                        methodStub("alwaysPasses").answer("getDescription", " logs a user in ")
                                .as(ITestNGMethod.class)).displayName,
                "logs a user in", "@Test(description) wins");
        assertEquals(TestRefs.fromMethod(method("alwaysPasses")).displayName, "alwaysPasses");
        // TestNG hands out an empty string, not null, when no description is set
        assertEquals(TestRefs.fromMethod(
                        methodStub("alwaysPasses").answer("getDescription", "")
                                .as(ITestNGMethod.class)).displayName,
                "alwaysPasses");
    }

    @Test
    public void nativeGroupsBecomeTags() {
        List<String> tags = TestRefs.groups(methodStub("alwaysPasses")
                .answer("getGroups", new String[]{"smoke", " api ", "", null})
                .as(ITestNGMethod.class));
        assertEquals(tags, Arrays.asList("smoke", "api"), "trimmed, blanks dropped");
        assertEquals(TestRefs.groups(method("alwaysPasses")), Collections.<String>emptyList());
        assertEquals(TestRefs.groups(null), Collections.<String>emptyList());
    }

    // ------------------------------------------------------------------ invocation parameters
    @Test
    public void invocationParametersCarryRealValuesUnderTheirArgumentNames() {
        List<Object[]> params = TestRefs.invocationParameters(
                result(method("withArgs", String.class), "chrome"));
        assertEquals(params.size(), 1);
        assertEquals(params.get(0)[0], "browser", "real argument name needs -parameters");
        assertEquals(params.get(0)[1], "chrome");
    }

    @Test
    public void invocationParametersSkipTheArgumentsTestNgInjects() {
        // ITestContext / Method / XmlTest are framework injections, not user data
        ITestNGMethod method = method("injected", ITestContext.class, String.class, Method.class,
                XmlTest.class);
        List<Object[]> params = TestRefs.invocationParameters(
                result(method, null, "chrome", null, null));
        assertEquals(params.size(), 1, "only the user argument survives");
        assertEquals(params.get(0)[0], "browser");
        assertEquals(params.get(0)[1], "chrome");
    }

    @Test
    public void undeclaredArgumentsAreRecognizedByTheirValueType() {
        // more values than declared parameters (factory / injection offsets): fall back to the
        // runtime type of the value, and name what is left argN
        List<Object[]> params = TestRefs.invocationParameters(
                result(method("withArgs", String.class), "chrome", new XmlTest()));
        assertEquals(params.size(), 1);
        assertEquals(params.get(0)[0], "browser");
        assertTrue(TestRefs.invocationParameters(result(method("alwaysPasses"))).isEmpty(),
                "a method without arguments has no invocation parameters");
    }

    // ------------------------------------------------------------------ stubs
    /**
     * Answers exactly the interface methods the factory calls; everything else gets the type's
     * zero value. {@code getAttribute}/{@code setAttribute} are backed by a real map, because the
     * invocation key lives there.
     */
    private static final class Stub implements InvocationHandler {

        private final Map<String, Object> answers = new LinkedHashMap<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        Stub answer(String method, Object value) {
            answers.put(method, value);
            return this;
        }

        @SuppressWarnings("unchecked")
        <T> T as(Class<T> iface) {
            return (T) Proxy.newProxyInstance(TestRefsTest.class.getClassLoader(),
                    new Class<?>[]{iface}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "getAttribute":
                    return attributes.get(String.valueOf(args[0]));
                case "setAttribute":
                    attributes.put(String.valueOf(args[0]), args[1]);
                    return null;
                case "removeAttribute":
                    return attributes.remove(String.valueOf(args[0]));
                case "getAttributeNames":
                    return new LinkedHashSet<>(attributes.keySet());
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "stub of " + method.getDeclaringClass().getSimpleName();
                default:
                    break;
            }
            return answers.containsKey(name) ? answers.get(name) : zero(method.getReturnType());
        }

        private static Object zero(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == double.class) {
                return 0.0d;
            }
            if (type == float.class) {
                return 0.0f;
            }
            if (type == char.class) {
                return (char) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            return 0;
        }
    }

    private static Stub methodStub(String name, Class<?>... parameterTypes) {
        Method javaMethod;
        try {
            javaMethod = Sample.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        return new Stub()
                .answer("getMethodName", name)
                .answer("getRealClass", Sample.class)
                .answer("getConstructorOrMethod", new ConstructorOrMethod(javaMethod))
                .answer("getTestClass", new Stub().answer("getRealClass", Sample.class)
                        .as(ITestClass.class));
    }

    private static ITestNGMethod method(String name, Class<?>... parameterTypes) {
        return methodStub(name, parameterTypes).as(ITestNGMethod.class);
    }

    private static ITestResult result(ITestNGMethod method, Object... parameters) {
        return new Stub()
                .answer("getMethod", method)
                .answer("getTestClass", new Stub().answer("getRealClass", Sample.class)
                        .as(IClass.class))
                .answer("getParameters", parameters)
                .as(ITestResult.class);
    }
}
