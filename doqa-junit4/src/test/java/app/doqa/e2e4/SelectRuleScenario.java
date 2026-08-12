package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import app.doqa.junit4.DoqaSelectRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * Mode-0 E2E fixture for {@link DoqaSelectRule} - the deselection path of suites that cannot use
 * {@code DoqaRunner} because they already have a runner of their own. A rule cannot remove a test
 * from the run, so the deselected one starts, is aborted with an assumption failure (body never
 * executed, JUnit calls it skipped) and is reported nowhere: its id is not part of the run.
 */
public class SelectRuleScenario {

    public static int selectedExecuted;
    public static int deselectedExecuted;

    @Rule
    public final DoqaSelectRule doqa = new DoqaSelectRule();

    @Test
    @DoqaId("E2E4-RULE-1")
    public void selectedTest() {
        selectedExecuted++;
    }

    @Test
    @DoqaId("E2E4-RULE-2")
    public void deselectedTest() {
        deselectedExecuted++;
    }
}
