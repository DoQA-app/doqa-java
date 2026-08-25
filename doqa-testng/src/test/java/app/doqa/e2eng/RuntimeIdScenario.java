package app.doqa.e2eng;

import app.doqa.Doqa;
import app.doqa.annotations.DoqaId;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Mode-0 E2E fixture for ids pinned at runtime: one autotest per data-provider row, judged at
 * {@code Doqa.addExternalId} - the interceptor sees the method, not the row.
 */
public class RuntimeIdScenario {

    public static int selectedExecuted;
    public static int deselectedExecuted;

    @DataProvider(name = "rows")
    public static Object[][] rows() {
        return new Object[][]{{"1"}, {"2"}};
    }

    @Test(dataProvider = "rows")
    @DoqaId("E2ENG-RUN-{row}")
    public void perRow(String row) {
        Doqa.addExternalId("E2ENG-RUN-" + row);
        if ("1".equals(row)) {
            selectedExecuted++;
        } else {
            deselectedExecuted++;
        }
    }
}
