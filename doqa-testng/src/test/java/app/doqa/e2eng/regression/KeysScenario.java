package app.doqa.e2eng.regression;

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
import io.qameta.allure.AllureId;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class KeysScenario {

    @BeforeMethod
    public void setUp() {
        Doqa.step("prepare");
    }

    @AfterMethod
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

    @Test(description = "Transfer [DOQA-77] works")
    public void idInDescription() {
    }

    @Test(description = "Plain description")
    public void describedHash() {
    }

    @Test(testName = "Custom test name")
    public void testNameOutsideHash() {
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

    @Test(groups = {"fast"})
    @DoqaTags({"extra"})
    @DoqaLabels({"owner:qa"})
    @DoqaCaseIds({11, 12})
    @DoqaTitle("Annotated title")
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

    @DataProvider(name = "payments")
    public Object[][] payments() {
        return new Object[][]{{"EUR", 10}, {"USD", 20}};
    }

    @Test(dataProvider = "payments")
    public void collapsed(String currency, int amount) {
        Assert.assertNotNull(currency);
    }

    @Test(dataProvider = "payments")
    @DoqaId("PAYNG-{currency}")
    @DoqaDisplayName("Pay {amount} in {currency}")
    public void placeholders(String currency, int amount) {
        Assert.assertTrue(amount > 0);
    }

    @Test(invocationCount = 2)
    public void repeated() {
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
    public void skipSignal() {
        throw new SkipException("not here");
    }
}
