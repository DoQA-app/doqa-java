package app.doqa.e2eng;

import app.doqa.Doqa;
import app.doqa.annotations.DoqaCaseIds;
import app.doqa.annotations.DoqaId;
import app.doqa.annotations.DoqaLabels;
import app.doqa.annotations.DoqaLink;
import app.doqa.annotations.DoqaLinks;
import app.doqa.annotations.DoqaTitle;
import app.doqa.annotations.Step;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * E2E demo suite executed by {@code SuiteRunEndToEndTest} through a nested TestNG run against a fake
 * DoQA backend. Deliberately outside {@code app.doqa.testng} so the AspectJ weaver (narrowed to this
 * package in the test aop.xml) weaves the {@code @Step} helpers like real host test code. The class
 * name intentionally does not match surefire's {@code *Test} patterns - it must only run inside the
 * nested run.
 *
 * <p>Covers the whole per-test surface in one class: class + method fixtures with steps and
 * attachments of their own, the annotation set, declarative and explicit steps, every outcome, a
 * test disabled with {@code enabled = false} (no TestNG events at all) and one skipped by an unmet
 * dependency.
 */
@DoqaLabels({"e2eng-class"})
public class DemoLoginScenario {

    @BeforeClass
    public void beforeClassInit() {
        Doqa.step("prepare db");
        Doqa.addAttachment("init.log", "class init ok");
    }

    @AfterClass
    public void afterClassCleanup() {
    }

    @BeforeMethod
    public void setUp() {
    }

    @AfterMethod
    public void tearDown() {
        // the reason the adapter defers sending: this runs AFTER the final ITestListener callback
        Doqa.step("close browser");
    }

    @Test(groups = {"native-group"})
    @DoqaId("E2ENG-LOGIN-1")
    @DoqaTitle("Login works")
    @DoqaLabels({"smoke"})
    @DoqaLinks({@DoqaLink(url = "http://tracker/BUG-1", type = "defect", title = "known bug")})
    @DoqaCaseIds({901})
    public void loginHappyPath() throws Exception {
        Doqa.addParameter("browser", "chrome");
        Doqa.addMessage("hello from runtime");
        Doqa.step("open login page", () -> {
            annotatedHelper();
            openPage("home");
        });
        Path shot = Files.createTempFile("doqa-e2eng-shot", ".png");
        Files.write(shot, new byte[]{1, 2, 3});
        Doqa.addAttachments(shot.toString());
    }

    @Step("annotated helper")
    void annotatedHelper() {
    }

    @Step("open {page} page")
    void openPage(String page) {
    }

    @Test
    public void assertionFails() {
        Assert.fail("boom");
    }

    @Test
    public void infrastructureBreaks() {
        throw new IllegalStateException("io error");
    }

    /** A matched {@code expectedExceptions} passes WITH a throwable on the result - a classic trap. */
    @Test(expectedExceptions = IllegalArgumentException.class)
    @DoqaId("E2ENG-EXPECTED-1")
    public void expectedExceptionPasses() {
        throw new IllegalArgumentException("expected by the test");
    }

    @Test(enabled = false)
    public void skippedByFlag() {
    }

    @Test(dependsOnMethods = "assertionFails")
    @DoqaId("E2ENG-DEP-1")
    public void skippedByDependency() {
    }
}
