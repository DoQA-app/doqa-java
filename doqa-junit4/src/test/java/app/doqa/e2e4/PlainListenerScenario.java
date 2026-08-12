package app.doqa.e2e4;

import app.doqa.Doqa;
import app.doqa.annotations.DoqaId;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Baseline-layer E2E fixture: NO {@code @RunWith(DoqaRunner.class)}, so only what the run listener
 * can observe on its own is reported. Two {@code @BeforeClass} methods on purpose - a listener can
 * merely bracket the whole block between the suite and the first test event, so they collapse into
 * one aggregate node, while {@code @Before}/{@code @After} produce no nodes at all and steps taken
 * inside them land in the default CALL bucket.
 */
public class PlainListenerScenario {

    @BeforeClass
    public static void openConnection() {
        Doqa.step("connect", () -> { });
    }

    @BeforeClass
    public static void seedData() {
    }

    @AfterClass
    public static void closeConnection() {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    @DoqaId("E2E4-PLAIN-1")
    public void reportsWithoutRunner() {
        Doqa.step("plain step", () -> { });
    }
}
