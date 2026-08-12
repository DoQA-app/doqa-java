package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import org.testng.annotations.Test;

/**
 * Mode-0 fixture for {@code dependsOnGroups}: the whole group the kept test depends on is outside the
 * run. A group left without a single method makes TestNG fail the {@code <test>} block ("depends on
 * nonexistent group"), so the interceptor has to pull the group members back in - they execute, but
 * their results stay out of the run.
 */
public class SelectGroupScenario {

    public static int groupExecuted;
    public static int dependentExecuted;

    @Test(groups = {"prep"})
    @DoqaId("E2ENG-GRP-PREP")
    public void prepares() {
        groupExecuted++;
    }

    @Test(dependsOnGroups = {"prep"})
    @DoqaId("E2ENG-GRP-1")
    public void needsTheGroup() {
        dependentExecuted++;
    }
}
