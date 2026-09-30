package app.doqa.cuke4;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(features = "classpath:cuke4", glue = "app.doqa.cuke4.steps",
        plugin = "app.doqa.cucumber.DoqaCucumberPlugin")
public class JointCucumberRunner {
}
