package app.doqa.cucumber.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/bank")
@ConfigurationParameter(key = "cucumber.glue", value = "app.doqa.cucumber.e2e.steps")
@ConfigurationParameter(key = "cucumber.plugin", value = "app.doqa.cucumber.DoqaCucumberPlugin")
public class BankSuite {
}
