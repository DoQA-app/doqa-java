package app.doqa.cukeng;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;

@CucumberOptions(features = "classpath:cukeng", glue = "app.doqa.cukeng.steps")
public class NoPluginCucumberRunner extends AbstractTestNGCucumberTests {
}
