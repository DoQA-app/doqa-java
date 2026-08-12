package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Skipped-class E2E fixture: {@code @Ignore} on the class fires ONE childless {@code testIgnored}
 * event, so the listener has to expand the class reflectively to report every test as skipped.
 */
@Ignore("maintenance window")
public class IgnoredClassScenario {

    @Test
    @DoqaId("E2E4-IGN-1")
    public void first() {
    }

    @Test
    @DoqaId("E2E4-IGN-2")
    public void second() {
    }
}
