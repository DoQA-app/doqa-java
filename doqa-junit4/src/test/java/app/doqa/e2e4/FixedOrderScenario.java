package app.doqa.e2e4;

import app.doqa.annotations.DoqaId;
import app.doqa.junit4.DoqaRunner;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/**
 * Pinned-order E2E fixture: the author fixed the execution order with {@code @FixMethodOrder}, so
 * the adapter must leave it alone even when the run's plan asks for a different sequence. The plan
 * used by the test lists these ids in exactly the reverse of the name order.
 */
@RunWith(DoqaRunner.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FixedOrderScenario {

    @Test
    @DoqaId("E2E4-FIX-A")
    public void aFirstByName() {
        OrderScenario.EXECUTED.add("E2E4-FIX-A");
    }

    @Test
    @DoqaId("E2E4-FIX-B")
    public void bSecondByName() {
        OrderScenario.EXECUTED.add("E2E4-FIX-B");
    }
}
