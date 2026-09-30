package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import app.doqa.cucumber.e2e.AllFeaturesJUnit4Runner;
import app.doqa.cucumber.e2e.AllFeaturesTestNGRunner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

class SelectiveRunTest {

    private static final String SIMPLE = Keys.hash("features/bank/transfer.feature#Simple transfer");
    private static final Set<String> SELECTED = new TreeSet<>(List.of(SIMPLE, "transfer-alice",
            "DOQA-501"));

    private Harness harness;

    @BeforeEach
    void setUp() {
        harness = new Harness();
        StringBuilder json = new StringBuilder("{\"autotests\":[");
        for (String id : SELECTED) {
            json.append(json.charAt(json.length() - 1) == '[' ? "" : ",")
                    .append("{\"externalId\":\"").append(id).append("\"}");
        }
        harness.selectiveResponse = json.append("]}").toString();
        harness.configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void platformRunsOnlyTheSelectedScenariosUnderEveryNamingStrategy() {
        for (Map<String, String> strategy : Keys.namingStrategies()) {
            harness.recorded.clear();
            harness.configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
            TestExecutionSummary summary = Harness.platform(strategy,
                    selectClasspathResource("features/bank"));
            assertEquals(3, summary.getTestsStartedCount(), "executed under " + strategy);
            assertEquals(0, summary.getTestsSkippedCount(), "nothing left as skipped: " + strategy);
            assertEquals(SELECTED, PlatformEndToEndTest.ids(harness.results()), "reported: " + strategy);
            assertEquals(new TreeSet<>(List.of("Over the limit", "Simple transfer", "Transfer 10 to alice")),
                    new TreeSet<>(Harness.names(harness.results())), strategy.toString());
        }
    }

    @Test
    void junit4RunsEverythingAndReportsOnlyTheSelected() {
        org.junit.runner.Result result = Harness.junit4(AllFeaturesJUnit4Runner.class);
        assertTrue(result.getRunCount() > 3, "cucumber-junit cannot deselect: " + result.getRunCount());
        assertEquals(SELECTED, PlatformEndToEndTest.ids(harness.results()));
    }

    @Test
    void testngRunsTheSelectedAndReportsOnlyThem() {
        Harness.testng(AllFeaturesTestNGRunner.class);
        assertEquals(SELECTED, PlatformEndToEndTest.ids(harness.results()));
        assertEquals(new TreeSet<>(List.of("Over the limit", "Simple transfer", "Transfer 10 to alice")),
                new TreeSet<>(Harness.names(harness.results())));
    }

    @Test
    void scenariosFilteredByCucumberTagsAreNotReportedAsSkipped() {
        harness.configure(Map.of("doqa.adapterMode", "2"));
        Map<String, String> config = new HashMap<>();
        config.put("cucumber.filter.tags", "not @outline");
        Harness.platform(config, selectClasspathResource("features/bank"));
        List<Object> names = Harness.names(harness.results());
        assertTrue(names.contains("Simple transfer"), names.toString());
        assertTrue(names.stream().noneMatch(n -> String.valueOf(n).startsWith("Transfer ")),
                names.toString());
        assertTrue(harness.results().stream().noneMatch(r -> "skipped".equals(r.get("outcome"))));
    }
}
