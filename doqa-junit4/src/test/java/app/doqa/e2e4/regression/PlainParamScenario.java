package app.doqa.e2e4.regression;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PlainParamScenario {

    @Parameterized.Parameters(name = "{index}: @DOQA:9 {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"alpha"}, {"beta"}});
    }

    @Parameterized.Parameter
    public String value;

    @Test
    public void named() {
        Assert.assertNotNull(value);
    }
}
