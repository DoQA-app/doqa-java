package app.doqa.e2e;

import app.doqa.Doqa;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Dynamic-test E2E fixture: the factory container survives mode-0 discovery filtering whenever the
 * plan may place a selected autotest in it, and each dynamic test pins its stable id at runtime
 * via {@code Doqa.addExternalId}.
 * Runs only inside the nested launcher (name avoids surefire patterns).
 */
public class FactoryScenario {

    public static int executed;
    /** Times the factory itself ran. */
    public static int factoryCalls;

    @TestFactory
    List<DynamicTest> dynamicChecks() {
        factoryCalls++;
        return List.of(
                DynamicTest.dynamicTest("[1] first check", () -> {
                    Doqa.addExternalId("E2E-DYN-1");
                    executed++;
                }),
                DynamicTest.dynamicTest("[2] second check", () -> {
                    Doqa.addExternalId("E2E-DYN-2");
                    executed++;
                }));
    }
}
