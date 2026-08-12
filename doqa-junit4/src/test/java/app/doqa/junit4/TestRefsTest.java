package app.doqa.junit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.doqa.core.Attribution;
import app.doqa.core.TestRef;
import java.util.List;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.Description;

/**
 * Unit fixtures for {@link TestRefs} - the single factory every entry point of the adapter resolves
 * identities through. What is pinned here is the JUnit 4 specifics the shared core cannot know
 * about: the {@code method[suffix]} shape of a {@code Description}, the invocation parameters that
 * can be recovered from the suffix alone, and the fact that all invocations of one method collapse
 * onto a single fallback externalId.
 */
public class TestRefsTest {

    /** {@code @Category} marker, class scope. */
    public interface SlowSuite {
    }

    /** {@code @Category} marker, method scope. */
    public interface WebUi {
    }

    /** Reflection target: not a runnable test class (no runner is ever built for it). */
    @Category(SlowSuite.class)
    public static class Sample {

        @Category(WebUi.class)
        public void alwaysPasses() {
        }

        public void plain() {
        }
    }

    // ------------------------------------------------------------------ method name + suffix
    @Test
    public void cleanMethodNameStripsTheInvocationSuffix() {
        assertEquals("alwaysPasses", TestRefs.cleanMethodName("alwaysPasses"));
        assertEquals("alwaysPasses", TestRefs.cleanMethodName("alwaysPasses[0: value=alpha]"));
        assertEquals("test", TestRefs.cleanMethodName("test[0]"));
        assertNull(TestRefs.cleanMethodName(null));
    }

    @Test
    public void invocationSuffixIsTheBracketedPart() {
        assertNull(TestRefs.invocationSuffix("alwaysPasses"));
        assertEquals("0: value=alpha", TestRefs.invocationSuffix("alwaysPasses[0: value=alpha]"));
        assertEquals("0", TestRefs.invocationSuffix("test[0]"));
        assertNull(TestRefs.invocationSuffix(null));
    }

    // ------------------------------------------------------------------ invocation parameters
    @Test
    public void bareIndexSuffixCarriesNoParameters() {
        // the default @Parameters name is the index alone - it says nothing about the arguments
        assertTrue(TestRefs.invocationParameters("test[0]").isEmpty());
        assertTrue(TestRefs.invocationParameters("test").isEmpty());
        assertTrue(TestRefs.invocationParameters("test[]").isEmpty());
    }

    @Test
    public void namedIndexSuffixYieldsOneArgumentsParameter() {
        List<Object[]> params = TestRefs.invocationParameters("alwaysPasses[0: value=alpha]");
        assertEquals(1, params.size());
        assertEquals("arguments", params.get(0)[0]);
        assertEquals("value=alpha", params.get(0)[1]);
    }

    @Test
    public void freeFormSuffixIsKeptVerbatimAsTheArguments() {
        List<Object[]> params = TestRefs.invocationParameters("alwaysPasses[chrome on linux]");
        assertEquals(1, params.size());
        assertEquals("arguments", params.get(0)[0]);
        assertEquals("chrome on linux", params.get(0)[1]);
    }

    // ------------------------------------------------------------------ refs from descriptions
    @Test
    public void parameterizedIsTrueOnlyWhenTheNameCarriesASuffix() {
        assertFalse(refOf("alwaysPasses").parameterized);
        assertTrue(refOf("alwaysPasses[0: value=alpha]").parameterized);
    }

    @Test
    public void refFromTestDescriptionResolvesClassMethodAndDisplayName() {
        TestRef ref = refOf("alwaysPasses[0: value=alpha]");
        assertEquals(Sample.class.getName(), ref.fqcn);
        assertEquals("alwaysPasses", ref.methodName);
        assertEquals("alwaysPasses[0: value=alpha]", ref.displayName);
        assertEquals("", ref.methodParamTypes);
        assertEquals(Sample.class, ref.testClass);
        assertNotNull(ref.testMethod);
        assertEquals("alwaysPasses", ref.testMethod.getName());
    }

    @Test
    public void refFromClassDescriptionHasNoMethod() {
        // @Ignore on the class / a failing @BeforeClass report a childless class description
        TestRef ref = TestRefs.fromDescription(Description.createSuiteDescription(Sample.class));
        assertNull(ref.methodName);
        assertNull(ref.testMethod);
        assertEquals(Sample.class.getName(), ref.fqcn);
        // the display name of a class ref is the simple name of the FQCN (nested classes keep $)
        assertEquals("TestRefsTest$Sample", ref.displayName);
        assertFalse(ref.parameterized);
    }

    @Test
    public void keyIsTheDescriptionDisplayName() {
        Description description = Description.createTestDescription(Sample.class, "alwaysPasses");
        assertEquals(description.getDisplayName(), TestRefs.key(description));
        assertNull(TestRefs.key(null));
    }

    // ------------------------------------------------------------------ categories
    @Test
    public void categoriesReadTheSimpleNamesOfClassAndMethodAnnotations() throws Exception {
        List<String> categories = TestRefs.categories(Sample.class,
                Sample.class.getDeclaredMethod("alwaysPasses"));
        assertEquals(2, categories.size());
        assertEquals("SlowSuite", categories.get(0));   // class first
        assertEquals("WebUi", categories.get(1));       // then method
        assertEquals(1, TestRefs.categories(Sample.class,
                Sample.class.getDeclaredMethod("plain")).size());
        assertTrue(TestRefs.categories(null, null).isEmpty());
    }

    // ------------------------------------------------------------------ identity collapse
    @Test
    public void everyInvocationOfOneMethodResolvesToTheSameFallbackId() {
        // this is what folds the invocations of a Parameterized method into ONE autotest
        String alpha = Attribution.resolve(refOf("alwaysPasses[0: value=alpha]")).externalId;
        String beta = Attribution.resolve(refOf("alwaysPasses[1: value=beta]")).externalId;
        assertEquals(alpha, beta);
        assertTrue("adapter-prefixed fallback id: " + alpha, alpha.startsWith("junit4:"));
        // a non-parameterized invocation of the same method keeps its own identity
        assertNotEquals(alpha, Attribution.resolve(refOf("alwaysPasses")).externalId);
    }

    private static TestRef refOf(String methodName) {
        return TestRefs.fromDescription(
                Description.createTestDescription(Sample.class, methodName));
    }
}
