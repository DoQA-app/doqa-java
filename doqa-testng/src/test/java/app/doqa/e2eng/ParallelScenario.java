package app.doqa.e2eng;

import app.doqa.Doqa;
import app.doqa.annotations.DoqaId;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Parallel-execution E2E fixture, run under {@code parallel="methods"}: every step title names the
 * test it belongs to, so any leak of a step (or of a fixture) between concurrent invocations is
 * visible in the payload.
 */
public class ParallelScenario {

    @BeforeMethod
    public void setUp() {
    }

    @AfterMethod
    public void tearDown() {
    }

    @Test
    @DoqaId("E2ENG-PAR-1")
    public void one() {
        steps("E2ENG-PAR-1");
    }

    @Test
    @DoqaId("E2ENG-PAR-2")
    public void two() {
        steps("E2ENG-PAR-2");
    }

    @Test
    @DoqaId("E2ENG-PAR-3")
    public void three() {
        steps("E2ENG-PAR-3");
    }

    @Test
    @DoqaId("E2ENG-PAR-4")
    public void four() {
        steps("E2ENG-PAR-4");
    }

    private static void steps(String id) {
        Doqa.step("first of " + id, () -> Thread.sleep(20L));
        Doqa.step("second of " + id, () -> Thread.sleep(20L));
    }
}
