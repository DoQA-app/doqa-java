package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Multiple-failure E2E fixture: the body AND {@code @After} both throw, which JUnit reports as TWO
 * {@code testFailure} callbacks for one test (a {@code MultipleFailureException} unwrapped per
 * cause). The adapter must aggregate them into a single result whose outcome is decided by the
 * non-assertion throwable.
 */
public class AfterFailScenario {

    @After
    public void tearDownFails() {
        throw new IllegalStateException("teardown failed");
    }

    @Test
    @DoqaId("E2E4-AF-1")
    public void bodyAndTeardownFail() {
        Assert.fail("body failed");
    }
}
