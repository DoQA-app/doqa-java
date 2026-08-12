package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Data-provider E2E fixture: TestNG hands the ACTUAL argument values to the listener, so both halves
 * of the parameterized contract are observable - a method without a {@code {param}} placeholder
 * collapses into one autotest with several results, a method with one becomes an autotest per
 * invocation.
 */
public class DataProviderScenario {

    public static int placeholderExecuted;
    public static int collapsedExecuted;

    @DataProvider(name = "browsers")
    public Object[][] browsers() {
        return new Object[][]{{"chrome"}, {"firefox"}};
    }

    @Test(dataProvider = "browsers")
    @DoqaId("E2ENG-BROWSER-{browser}")
    public void placeholderPerInvocation(String browser) {
        placeholderExecuted++;
        Assert.assertNotNull(browser);
    }

    @Test(dataProvider = "browsers")
    public void collapsesToOneAutotest(String browser) {
        collapsedExecuted++;
        Assert.assertNotNull(browser);
    }
}
