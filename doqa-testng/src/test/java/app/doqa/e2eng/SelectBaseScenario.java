package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import org.testng.annotations.Test;

/**
 * Base class of {@link SelectScenario}: holds the test the subclass depends on. A dependency declared
 * in a base class is the case where TestNG's own qualified-name matching needs a second attempt
 * against the EXECUTING class, and the mode-0 interceptor has to reproduce it - dropping the
 * dependency would abort the whole {@code <test>} block.
 */
public class SelectBaseScenario {

    public static int baseExecuted;

    @Test
    @DoqaId("E2ENG-SEL-BASE")
    public void prepareInBase() {
        baseExecuted++;
    }
}
