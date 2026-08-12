package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import app.doqa.junit4.DoqaRunner;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Plan-ordering E2E fixture: explicit externalIds so the run's plan can position the methods, whose
 * declaration order deliberately disagrees with the plan. The shared execution log is also written
 * to by {@link FixedOrderScenario}.
 */
@RunWith(DoqaRunner.class)
public class OrderScenario {

    /** Execution log shared with {@link FixedOrderScenario}. */
    public static final List<String> EXECUTED = new CopyOnWriteArrayList<>();

    @Test
    @DoqaId("E2E4-ORD-1")
    public void alpha() {
        EXECUTED.add("E2E4-ORD-1");
    }

    @Test
    @DoqaId("E2E4-ORD-2")
    public void beta() {
        EXECUTED.add("E2E4-ORD-2");
    }

    @Test
    @DoqaId("E2E4-ORD-3")
    public void gamma() {
        EXECUTED.add("E2E4-ORD-3");
    }
}
