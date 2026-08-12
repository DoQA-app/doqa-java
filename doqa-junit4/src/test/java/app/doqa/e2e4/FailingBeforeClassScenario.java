package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Class-failure E2E fixture: {@code @BeforeClass} throws, so JUnit fires a single {@code testFailure}
 * carrying the CLASS description and no test events at all - the listener must synthesize a result
 * for every test of the class ("results are not lost") and mark the class-fixture node broken.
 */
public class FailingBeforeClassScenario {

    @BeforeClass
    public static void boom() {
        throw new IllegalStateException("infra down");
    }

    @Test
    @DoqaId("E2E4-BC-1")
    public void first() {
    }

    @Test
    @DoqaId("E2E4-BC-2")
    public void second() {
    }
}
