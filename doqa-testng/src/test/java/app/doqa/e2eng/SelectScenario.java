package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import org.testng.annotations.Test;

/**
 * Mode-0 (selective run) E2E fixture: the fake backend decides which external ids belong to the run,
 * so a deselected test must not be EXECUTED at all - which is what the counters prove, a report-time
 * gate alone would still run the body. {@link #dependsOnBase()} covers the TestNG-specific half: its
 * dependency lives in the base class and is NOT part of the run, yet dropping it would abort the
 * whole {@code <test>} block.
 */
public class SelectScenario extends SelectBaseScenario {

    public static int selectedExecuted;
    public static int deselectedExecuted;
    public static int dependentExecuted;

    @Test
    @DoqaId("E2ENG-SEL-1")
    public void selectedTest() {
        selectedExecuted++;
    }

    @Test
    @DoqaId("E2ENG-SEL-2")
    public void deselectedTest() {
        deselectedExecuted++;
    }

    @Test(dependsOnMethods = "app.doqa.e2eng.SelectBaseScenario.prepareInBase")
    @DoqaId("E2ENG-SEL-DEP")
    public void dependsOnBase() {
        dependentExecuted++;
    }
}
