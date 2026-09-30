package app.doqa.e2e.regression;

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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class KeysScenario {

    @BeforeEach
    void setUp() {
        Doqa.step("prepare");
    }

    @AfterEach
    void tearDown() {
        Doqa.step("clean up");
    }

    @Test
    void plainHash() {
        Doqa.step("outer", () -> helper("inner"));
    }

    @Step("helper {name}")
    void helper(String name) {
    }

    @Test
    @DisplayName("Transfer [DOQA-77] works")
    void idInDisplayName() {
    }

    @Test
    @DisplayName("Alternative @DOQA:88 form")
    void idInDisplayNameAlt() {
    }

    @Test
    @AllureId("321")
    void allureOnly() {
    }

    @Test
    @DoqaId("EXPLICIT-1")
    @AllureId("654")
    void explicitBeatsAllure() {
    }

    @Test
    @Tag("fast")
    @DoqaTags({"extra"})
    @DoqaLabels({"owner:qa"})
    @DoqaCaseIds({11, 12})
    @DoqaTitle("Annotated title")
    void annotated() {
        Doqa.addCaseIds(13);
        Doqa.addLabel("component", "payments");
    }

    @Test
    @DoqaNamespace("custom.ns")
    @DoqaClassName("Custom")
    void customCoordinates() {
    }

    @Test
    void runtimeId() {
        Doqa.addExternalId("RUNTIME-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"alpha", "beta"})
    void collapsed(String value) {
        Assertions.assertNotNull(value);
    }

    @ParameterizedTest
    @CsvSource({"EUR, 10", "USD, 20"})
    @DoqaId("PAY-{currency}")
    @DoqaDisplayName("Pay {amount} in {currency}")
    void placeholders(String currency, int amount) {
        Assertions.assertTrue(amount > 0);
    }

    @Test
    void failsAssertion() {
        Assertions.fail("boom");
    }

    @Test
    void breaks() {
        throw new IllegalStateException("io error");
    }

    @Test
    void assumptionSkips() {
        Assumptions.assumeTrue(false, "not here");
    }

    @Disabled("not today")
    @Test
    void disabled() {
    }

    @TestFactory
    Stream<DynamicTest> dynamic() {
        return List.of("one", "two").stream()
                .map(n -> DynamicTest.dynamicTest("dyn " + n, () -> Doqa.step("dyn step " + n)));
    }

    @Nested
    class Inner {
        @Test
        void nestedTest() {
        }
    }
}
