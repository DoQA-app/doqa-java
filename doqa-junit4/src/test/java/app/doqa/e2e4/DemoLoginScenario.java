package app.doqa.e2e4;

import app.doqa.Doqa;
import app.doqa.annotations.DoqaCaseIds;
import app.doqa.annotations.DoqaId;
import app.doqa.annotations.DoqaLabels;
import app.doqa.annotations.DoqaLink;
import app.doqa.annotations.DoqaLinks;
import app.doqa.annotations.DoqaTitle;
import app.doqa.annotations.Step;
import app.doqa.junit4.DoqaRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

/**
 * E2E demo suite executed by {@code CoreRunEndToEndTest} through a nested {@code JUnitCore} run
 * against a fake DoQA backend. Deliberately outside {@code app.doqa.junit4} so the AspectJ weaver
 * (narrowed to this package in the test aop.xml) weaves the {@code @Step} helpers like real host
 * test code. The class name intentionally does not match surefire's {@code *Test} patterns - it
 * must only run inside the nested run.
 */
@RunWith(DoqaRunner.class)
@DoqaLabels({"e2e-class"})
public class DemoLoginScenario {

    /** {@code @Category} marker - the JUnit 4 counterpart of a Jupiter {@code @Tag}. */
    public interface NativeTag {
    }

    @BeforeClass
    public static void beforeClassInit() {
        Doqa.step("prepare db", () -> { });
        Doqa.addAttachment("init.log", "class init ok");
    }

    @AfterClass
    public static void afterClassCleanup() {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    @Category(NativeTag.class)
    @DoqaId("E2E4-LOGIN-1")
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
        Path shot = Files.createTempFile("doqa-e2e-shot", ".png");
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

    @Ignore("not today")
    @Test
    public void skippedByAnnotation() {
    }
}
