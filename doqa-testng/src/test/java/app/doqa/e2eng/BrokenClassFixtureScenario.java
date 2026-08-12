package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Broken-{@code @BeforeClass} E2E fixture: every test of the class gets its own start + skip events,
 * and the class-fixture node - recorded once per class per run - is prepended to each of them with
 * the failure on it.
 */
public class BrokenClassFixtureScenario {

    public static int executed;

    @BeforeClass
    public void brokenClassSetup() {
        throw new IllegalStateException("class setup down");
    }

    @Test
    @DoqaId("E2ENG-BC-1")
    public void first() {
        executed++;
    }

    @Test
    @DoqaId("E2ENG-BC-2")
    public void second() {
        executed++;
    }
}
