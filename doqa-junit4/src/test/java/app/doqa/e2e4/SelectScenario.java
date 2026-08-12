package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import app.doqa.junit4.DoqaRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Mode-0 (selective run) E2E fixture for {@link DoqaRunner}: the fake backend puts only
 * {@code E2E4-SEL-1} in the run, so {@code E2E4-SEL-2} must not be EXECUTED at all - which is what
 * the counters prove, a report-time gate alone would still run the body.
 */
@RunWith(DoqaRunner.class)
public class SelectScenario {

    public static int selectedExecuted;
    public static int deselectedExecuted;

    @Test
    @DoqaId("E2E4-SEL-1")
    public void selectedTest() {
        selectedExecuted++;
    }

    @Test
    @DoqaId("E2E4-SEL-2")
    public void deselectedTest() {
        deselectedExecuted++;
    }
}
