package app.doqa.e2e4.regression;

import app.doqa.annotations.DoqaDisplayName;
import app.doqa.annotations.DoqaId;
import app.doqa.junit4.DoqaParametersRunnerFactory;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(DoqaParametersRunnerFactory.class)
public class ParamKeysScenario {

    @Parameterized.Parameters(name = "{index}: currency={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"EUR", 10}, {"USD", 20}});
    }

    @Parameterized.Parameter(0)
    public String currency;

    @Parameterized.Parameter(1)
    public int amount;

    @Test
    public void collapsed() {
        Assert.assertNotNull(currency);
    }

    @Test
    @DoqaId("PAY4-{currency}")
    @DoqaDisplayName("Pay {amount} in {currency}")
    public void placeholders() {
        Assert.assertTrue(amount > 0);
    }
}
