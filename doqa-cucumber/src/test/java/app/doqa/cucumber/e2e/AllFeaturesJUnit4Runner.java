package app.doqa.cucumber.e2e;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
        features = {"classpath:features/bank", "classpath:features/ru",
                "classpath:features/statuses", "classpath:features/extras"},
        glue = "app.doqa.cucumber.e2e.steps",
        plugin = "app.doqa.cucumber.DoqaCucumberPlugin")
public class AllFeaturesJUnit4Runner {
}
