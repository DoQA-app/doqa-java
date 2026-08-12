package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import app.doqa.junit4.DoqaParametersRunnerFactory;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Parameterized E2E fixture: {@code Parameterized} plus the DoQA runner factory, which is the only
 * place the ACTUAL argument values of an invocation are available. Proves both halves of the
 * parameterized contract - a method without a {@code {param}} placeholder collapses into one
 * autotest with several results, a method with one becomes an autotest per invocation.
 */
@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(DoqaParametersRunnerFactory.class)
public class ParamScenario {

    @Parameterized.Parameters(name = "{index}: value={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"alpha"}, {"beta"}});
    }

    @Parameterized.Parameter(0)
    public String value;

    @Test
    public void collapsesToOneAutotest() {
        Assert.assertNotNull(value);
    }

    @Test
    @DoqaId("E2E4-PARAM-{value}")
    public void placeholderPerInvocation() {
        Assert.assertNotNull(value);
    }
}
