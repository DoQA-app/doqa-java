package app.doqa.cucumber.e2e.steps;

import io.cucumber.java.After;
import io.cucumber.java.AfterStep;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeStep;
import io.cucumber.java.Scenario;
import java.nio.charset.StandardCharsets;

public class Hooks {

    @Before("@fail_before")
    public void failBefore() {
        throw new IllegalStateException("before hook broke");
    }

    @After("@fail_after")
    public void failAfter() {
        throw new AssertionError("after hook check failed");
    }

    @BeforeStep("@fail_before_step")
    public void failBeforeStep() {
        throw new IllegalStateException("before step hook broke");
    }

    @AfterStep("@fail_after_step")
    public void failAfterStep() {
        throw new AssertionError("after step check failed");
    }

    @Before("@hooked")
    public void openBrowser(Scenario scenario) {
        scenario.log("browser opened");
    }

    @AfterStep("@hooked")
    public void screenshot(Scenario scenario) {
        scenario.attach(new byte[] {1, 2, 3}, "image/png", "screenshot");
    }

    @After("@hooked")
    public void closeBrowser(Scenario scenario) {
        scenario.attach("closing".getBytes(StandardCharsets.UTF_8), "text/plain", "after.txt");
    }
}
