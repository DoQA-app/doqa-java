package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Broken-{@code @BeforeMethod} E2E fixture: TestNG still fires the test's own start and skip events,
 * so the adapter has to synthesize nothing - the test arrives as skipped, naming the fixture that
 * killed it, and the fixture node itself carries the failure.
 */
public class BrokenFixtureScenario {

    public static int executed;

    @BeforeMethod
    public void brokenSetup() {
        throw new IllegalStateException("setup down");
    }

    @Test
    @DoqaId("E2ENG-BF-1")
    public void neverRuns() {
        executed++;
    }
}
