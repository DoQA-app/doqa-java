package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutcomeTest {

    private static Outcome of(Status status, Throwable error) {
        return Outcome.of(new Result(status, Duration.ZERO, error), "Given x");
    }

    @Test
    void statusesMapOnePerOne() {
        assertEquals("passed", of(Status.PASSED, null).outcome);
        assertNull(of(Status.PASSED, null).message);
        assertEquals("failed", of(Status.FAILED, new AssertionError("a")).outcome);
        assertEquals("broken", of(Status.FAILED, new IllegalStateException("b")).outcome);
        assertEquals("b", of(Status.FAILED, new IllegalStateException("b")).message);
        assertEquals("skipped", of(Status.SKIPPED, null).outcome);
        assertEquals("skipped", of(Status.UNUSED, null).outcome);
        assertEquals("broken", of(Status.AMBIGUOUS, new RuntimeException("two")).outcome);
        assertEquals("two", of(Status.AMBIGUOUS, new RuntimeException("two")).message);
    }

    @Test
    void pendingIsSkippedAndUndefinedIsBrokenWithAnExplanation() {
        Outcome pending = of(Status.PENDING, new RuntimeException("TODO: implement me"));
        assertEquals("skipped", pending.outcome);
        assertTrue(pending.message.contains("not implemented"), pending.message);
        Outcome undefined = of(Status.UNDEFINED, null);
        assertEquals("broken", undefined.outcome);
        assertTrue(undefined.message.contains("\"Given x\""), undefined.message);
    }

    @Test
    void hookNameIsClassAndMethod() {
        assertEquals("Hooks.openBrowser",
                ScenarioRun.hookName("com.acme.Hooks.openBrowser(io.cucumber.java.Scenario)"));
        assertEquals("Hooks.close", ScenarioRun.hookName("Hooks.close()"));
        assertEquals("hook", ScenarioRun.hookName(null));
    }
}
