package app.doqa.cucumber.e2e.steps;

import app.doqa.Doqa;
import io.cucumber.java.PendingException;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import java.nio.charset.StandardCharsets;
import org.opentest4j.TestAbortedException;

public class StatusSteps {

    private Scenario scenario;

    @io.cucumber.java.Before(value = "@hooked", order = 0)
    public void remember(Scenario scenario) {
        this.scenario = scenario;
    }

    @Given("a passing step")
    public void passing() {
    }

    @When("an assertion fails")
    public void assertionFails() {
        throw new AssertionError("expected 1 but was 2");
    }

    @Given("a runtime exception is thrown")
    public void runtimeException() {
        throw new IllegalStateException("connection refused");
    }

    @Given("a pending step")
    public void pending() {
        throw new PendingException();
    }

    @Given("^an ambiguous (.*)$")
    public void ambiguousOne(String what) {
    }

    @Given("^an (.*) step$")
    public void ambiguousTwo(String what) {
    }

    @Given("the test is aborted")
    public void aborted() {
        throw new TestAbortedException("not on this environment");
    }

    @Given("a step that attaches and logs")
    public void attachesAndLogs() {
        scenario.attach("step body".getBytes(StandardCharsets.UTF_8), "text/plain", "step.txt");
        scenario.log("step log line");
    }

    @When("a step with nested DoQA steps")
    public void nested() {
        Doqa.step("open the page", () -> Doqa.addMessage("page opened"));
        Doqa.step("check the title", () -> Doqa.addAttachment("title.txt", "Title"));
        Doqa.addCaseIds(77);
    }

    @Given("the text {}")
    public void text(String value) {
    }

    @Given("parallel step {int}.{int}")
    public void parallel(int scenario, int step) throws InterruptedException {
        Executed.add("parallel " + scenario + "." + step + " on " + Thread.currentThread().getName());
        Thread.sleep(20);
    }
}
