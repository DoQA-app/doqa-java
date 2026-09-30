package app.doqa.cucumber.e2e;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;

@CucumberOptions(
        features = {"classpath:features/bank", "classpath:features/ru",
                "classpath:features/statuses", "classpath:features/extras"},
        glue = "app.doqa.cucumber.e2e.steps",
        plugin = "app.doqa.cucumber.DoqaCucumberPlugin")
public class AllFeaturesTestNGRunner extends AbstractTestNGCucumberTests {
}
