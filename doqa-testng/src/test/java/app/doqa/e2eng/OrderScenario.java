package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.testng.annotations.Test;

/**
 * Plan-ordering E2E fixture: explicit externalIds so the run's plan can position the methods, whose
 * declaration order deliberately disagrees with the plan. The order the interceptor returns wins over
 * declaration order and over {@code @Test(priority)}.
 */
public class OrderScenario {

    /** Execution log, read by the end-to-end assertions. */
    public static final List<String> EXECUTED = new CopyOnWriteArrayList<>();

    @Test(priority = 1)
    @DoqaId("E2ENG-ORD-1")
    public void alpha() {
        EXECUTED.add("E2ENG-ORD-1");
    }

    @Test(priority = 2)
    @DoqaId("E2ENG-ORD-2")
    public void beta() {
        EXECUTED.add("E2ENG-ORD-2");
    }

    @Test(priority = 3)
    @DoqaId("E2ENG-ORD-3")
    public void gamma() {
        EXECUTED.add("E2ENG-ORD-3");
    }
}
