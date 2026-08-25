package app.doqa.e2e4;

import app.doqa.Doqa;
import org.junit.Test;

/**
 * Mode-0 E2E fixture for ids pinned at runtime: a plain suite (no {@code DoqaRunner}, no
 * {@code DoqaSelectRule}), where the id is declared inside the body and judged at
 * {@code Doqa.addExternalId}.
 */
public class RuntimeIdScenario {

    public static int selectedExecuted;
    public static int deselectedExecuted;

    @Test
    public void selectedTest() {
        Doqa.addExternalId("E2E4-RUN-1");
        selectedExecuted++;
    }

    @Test
    public void deselectedTest() {
        Doqa.addExternalId("E2E4-RUN-2");
        deselectedExecuted++;
    }
}
