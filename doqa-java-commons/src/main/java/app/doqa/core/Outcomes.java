package app.doqa.core;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Outcome taxonomy helpers shared by the facade and framework adapters: assertion failures map
 * to {@code failed} (a product defect signal), everything else to {@code broken}
 * (an infrastructure/test defect signal) - the split DoQA error clustering builds on.
 *
 * <p>Internal adapter API.
 */
public final class Outcomes {

    private Outcomes() {
    }

    /** Step/test outcome for a throwable: assertion failures are "failed", the rest "broken". */
    public static String failureOutcome(Throwable t) {
        return isAssertion(t) ? "failed" : "broken";
    }

    public static boolean isAssertion(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof AssertionError) {
            return true;
        }
        for (Class<?> c = t.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if ("org.opentest4j.AssertionFailedError".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    public static String messageOf(Throwable t) {
        if (t == null) {
            return null;
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
    }

    /** The printed stack trace of {@code t} - the {@code traces} field of a result. */
    public static String stackTrace(Throwable t) {
        if (t == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
