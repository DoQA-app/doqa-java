package app.doqa.e2e4.regression;

import app.doqa.Doqa;
import app.doqa.annotations.DoqaCaseIds;
import app.doqa.annotations.DoqaClassName;
import app.doqa.annotations.DoqaDisplayName;
import app.doqa.annotations.DoqaId;
import app.doqa.annotations.DoqaLabels;
import app.doqa.annotations.DoqaNamespace;
import app.doqa.annotations.DoqaTags;
import app.doqa.annotations.DoqaTitle;
import app.doqa.annotations.Step;
import app.doqa.junit4.DoqaRunner;
import io.qameta.allure.AllureId;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

@RunWith(DoqaRunner.class)
public class KeysScenario {

    public interface Fast {
    }

    @Before
    public void setUp() {
        Doqa.step("prepare");
    }

    @After
    public void tearDown() {
        Doqa.step("clean up");
    }

    @Test
    public void plainHash() {
        Doqa.step("outer", () -> helper("inner"));
    }

    @Step("helper {name}")
    void helper(String name) {
    }

    @Test
    @AllureId("321")
    public void allureOnly() {
    }

    @Test
    @DoqaId("EXPLICIT-1")
    @AllureId("654")
    public void explicitBeatsAllure() {
    }

    @Test
    @Category(Fast.class)
    @DoqaTags({"extra"})
    @DoqaLabels({"owner:qa"})
    @DoqaCaseIds({11, 12})
    @DoqaTitle("Annotated title")
    @DoqaDisplayName("Annotated display name")
    public void annotated() {
        Doqa.addCaseIds(13);
        Doqa.addLabel("component", "payments");
    }

    @Test
    @DoqaNamespace("custom.ns")
    @DoqaClassName("Custom")
    public void customCoordinates() {
    }

    @Test
    public void runtimeId() {
        Doqa.addExternalId("RUNTIME-1");
    }

    @Test
    public void failsAssertion() {
        Assert.fail("boom");
    }

    @Test
    public void breaks() {
        throw new IllegalStateException("io error");
    }

    @Test
    public void assumptionSkips() {
        Assume.assumeTrue("not here", false);
    }

    @Ignore("not today")
    @Test
    public void ignored() {
    }
}
