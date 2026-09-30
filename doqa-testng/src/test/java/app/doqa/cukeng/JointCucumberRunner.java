package app.doqa.cukeng;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import org.testng.annotations.Test;

@CucumberOptions(features = "classpath:cukeng", glue = "app.doqa.cukeng.steps",
        plugin = "app.doqa.cucumber.DoqaCucumberPlugin")
public class JointCucumberRunner extends AbstractTestNGCucumberTests {

    @Test
    public void runnerSmoke() {
    }
}
