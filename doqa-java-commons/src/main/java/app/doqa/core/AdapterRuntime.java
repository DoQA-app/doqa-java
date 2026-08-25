package app.doqa.core;

import java.util.function.Function;

/**
 * Identity of the concrete framework adapter running on top of commons. Adapter entry points
 * (listener / filter / extension) call {@link #configure} once in a static initializer, before
 * any attribution or reporting happens.
 *
 * <p>Internal adapter API - not for test code.
 */
public final class AdapterRuntime {

    private static volatile String framework = "jvm";
    private static volatile String frameworkLabel = "jvm";
    private static volatile Function<String, RuntimeException> skipSignal;

    private AdapterRuntime() {
    }

    /**
     * @param frameworkId   stable id used as the signature-hash externalId prefix
     *                      (e.g. {@code junit5}) - changing it changes every fallback id
     * @param label         Allure {@code framework} label of the file sink
     *                      (e.g. {@code junit-platform})
     */
    public static void configure(String frameworkId, String label) {
        if (frameworkId != null && !frameworkId.isEmpty()) {
            framework = frameworkId;
        }
        if (label != null && !label.isEmpty()) {
            frameworkLabel = label;
        }
    }

    public static String framework() {
        return framework;
    }

    public static String frameworkLabel() {
        return frameworkLabel;
    }

    /**
     * How this framework skips a test: JUnit 5 {@code TestAbortedException}, JUnit 4
     * {@code AssumptionViolatedException}, TestNG {@code SkipException}. Registered by the adapter
     * entry point - commons cannot reference framework types.
     */
    public static void configureSkipSignal(Function<String, RuntimeException> factory) {
        if (factory != null) {
            skipSignal = factory;
        }
    }

    /** Framework-native "skip this test" exception, or {@code null} when none is registered. */
    public static RuntimeException skipSignal(String message) {
        Function<String, RuntimeException> factory = skipSignal;
        if (factory == null) {
            return null;
        }
        try {
            return factory.apply(message);
        } catch (Throwable t) {
            return null;
        }
    }
}
