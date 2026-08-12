package app.doqa.junit4;

import org.junit.runner.Runner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.parameterized.ParametersRunnerFactory;
import org.junit.runners.parameterized.TestWithParameters;

/**
 * Makes {@code Parameterized} build {@link DoqaRunnerWithParameters} instead of the default runner,
 * which is the only way to learn the actual argument values of an invocation - JUnit 4 publishes
 * them neither in the {@code Description} nor to a listener, so without this factory the report
 * carries the rendered invocation name only and {@code {param}} placeholders cannot be substituted.
 *
 * <p>Registration: {@code @Parameterized.UseParametersRunnerFactory(DoqaParametersRunnerFactory.class)}
 * next to {@code @RunWith(Parameterized.class)}.
 */
public class DoqaParametersRunnerFactory implements ParametersRunnerFactory {

    @Override
    public Runner createRunnerForTestWithParameters(TestWithParameters test)
            throws InitializationError {
        return new DoqaRunnerWithParameters(test);
    }
}
