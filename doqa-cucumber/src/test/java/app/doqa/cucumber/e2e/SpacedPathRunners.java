package app.doqa.cucumber.e2e;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

/** One feature in a directory with a space and Cyrillic, reached through classpath: and through a file path. */
public final class SpacedPathRunners {

    public static final String DIR = "features/with space/каталог фич";

    private SpacedPathRunners() {
    }

    // cucumber-core rejects a raw space in a classpath: option, so the option itself is percent-encoded
    @RunWith(Cucumber.class)
    @CucumberOptions(
            features = "classpath:features/with%20space/каталог%20фич",
            glue = "app.doqa.cucumber.e2e.steps",
            plugin = "app.doqa.cucumber.DoqaCucumberPlugin")
    public static class Classpath {
    }

    @RunWith(Cucumber.class)
    @CucumberOptions(
            features = "src/test/resources/features/with space/каталог фич",
            glue = "app.doqa.cucumber.e2e.steps",
            plugin = "app.doqa.cucumber.DoqaCucumberPlugin")
    public static class File {
    }
}
