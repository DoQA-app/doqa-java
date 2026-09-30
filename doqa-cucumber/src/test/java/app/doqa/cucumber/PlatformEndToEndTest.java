package app.doqa.cucumber;

import static app.doqa.cucumber.Harness.allNamed;
import static app.doqa.cucumber.Harness.byName;
import static app.doqa.cucumber.Harness.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectDirectory;

import app.doqa.cucumber.e2e.BankSuite;
import app.doqa.cucumber.e2e.StatusesSuite;
import app.doqa.cucumber.e2e.steps.Executed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

class PlatformEndToEndTest {

    private Harness harness;

    @BeforeEach
    void setUp() {
        harness = new Harness().configure(Map.of("doqa.adapterMode", "2"));
        Executed.LOG.clear();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void scenariosBecomeAutotestsWithGherkinSteps() {
        Harness.platform(Map.of(), selectClasspathResource("features/bank"));

        List<Map<String, Object>> defs = harness.defs();
        Map<String, Object> simple = byName(defs, "Simple transfer");
        assertEquals(Keys.hash("features/bank/transfer.feature#Simple transfer"),
                simple.get("external_id"));
        assertEquals("features/bank", simple.get("namespace"));
        assertEquals("Money transfer", simple.get("classname"));
        assertEquals("Simple transfer", simple.get("runner_method"));
        assertEquals(List.of("bank"), simple.get("tags"));
        assertEquals("[10]", String.valueOf(simple.get("case_ids")));
        assertEquals(Arrays.asList("Given an account with 100 EUR", "When I transfer 30 EUR",
                "Then the balance is 70 EUR"), titles(simple.get("steps")));
        assertEquals(Arrays.asList("step", "step", "step"), kinds(simple.get("steps")));

        Map<String, Object> rule = byName(defs, "Over the limit");
        assertEquals("DOQA-501", rule.get("external_id"));
        assertEquals("DOQA-502", byName(defs, "[DOQA-502] Within the limit").get("external_id"));

        Map<String, Object> result = byName(harness.results(), "Simple transfer");
        assertEquals("passed", result.get("outcome"));
        assertEquals(List.of("passed", "passed", "passed"), outcomes(result.get("step_results")));
    }

    @Test
    void outlineIsOneAutotestPerKeyWithRowParameters() {
        Harness.platform(Map.of(), selectClasspathResource("features/bank"));

        List<Map<String, Object>> defs = allNamed(harness.defs(), "Transfer <amount> to <who>");
        Set<Object> ids = defs.stream().map(d -> d.get("external_id")).collect(Collectors.toSet());
        assertEquals(new TreeSet<>(Arrays.asList("transfer-alice", "transfer-bob",
                Keys.hash("features/bank/transfer.feature#Transfer <amount> to <who>"))),
                new TreeSet<>(ids));
        for (Map<String, Object> def : defs) {
            assertEquals(Arrays.asList("Given an account with 100 EUR", "When I transfer <amount> EUR",
                    "Then the balance is <left> EUR"), titles(def.get("steps")));
            assertEquals("Transfer <amount> to <who>", def.get("runner_method"));
            assertEquals("[10, 11]", String.valueOf(def.get("case_ids")));
        }
        assertTrue(((List<?>) defs.get(0).get("tags")).containsAll(List.of("bank", "outline")));

        Map<String, Object> eve = byName(harness.results(), "Transfer 5 to eve|x");
        assertEquals(Keys.hash("features/bank/transfer.feature#Transfer <amount> to <who>"),
                eve.get("external_id"));
        assertEquals("[{name=amount, value=5}, {name=who, value=eve|x}, {name=left, value=95}]",
                String.valueOf(eve.get("parameters")));
        assertEquals(Arrays.asList("Given an account with 100 EUR", "When I transfer 5 EUR",
                "Then the balance is 95 EUR"), titles(eve.get("step_results")));
        assertEquals("transfer-bob", byName(harness.results(), "Transfer 20 to bob").get("external_id"));
    }

    @Test
    void docStringAndDataTableAreStepAttachments() {
        Harness.platform(Map.of(), selectClasspathResource("features/bank"));

        Map<String, Object> result = byName(harness.results(), "Documents");
        List<Map<String, Object>> steps = list(result.get("step_results"));
        assertEquals("Given a document:", steps.get(1).get("title"));
        assertEquals(1, list(steps.get(1).get("attachments")).size());
        assertEquals("And a table:", steps.get(2).get("title"));
        assertEquals(1, list(steps.get(2).get("attachments")).size());
        assertEquals(2, harness.requests("POST", "/attachments").size());
        String uploads = harness.requests("POST", "/attachments").stream().map(r -> r.body)
                .collect(Collectors.joining());
        assertTrue(uploads.contains("hello\nworld"), uploads);
        assertTrue(uploads.contains("| a | b |\n| 1 | 2 |"), uploads);
    }

    @Test
    void russianFeatureKeepsItsKeywords() {
        Harness.platform(Map.of(), selectClasspathResource("features/ru"));

        Map<String, Object> def = byName(harness.defs(), "Простой перевод");
        assertEquals("Перевод по-русски", def.get("classname"));
        assertEquals(Arrays.asList("Дано an account with 100 EUR", "Когда I transfer 40 EUR",
                "Тогда the balance is 60 EUR"), titles(def.get("steps")));
        Map<String, Object> outline = byName(harness.defs(), "Перевод <amount>");
        assertEquals(Arrays.asList("Дано an account with 100 EUR", "Когда I transfer <amount> EUR",
                "Тогда the balance is <left> EUR"), titles(outline.get("steps")));
        assertEquals("passed", byName(harness.results(), "Перевод 1").get("outcome"));
    }

    @Test
    void cucumberStatusesMapToDoqaOutcomes() {
        TestExecutionSummary summary = Harness.platform(Map.of(),
                selectClasspathResource("features/statuses"));
        assertTrue(summary.getTestsFoundCount() > 0);

        List<Map<String, Object>> results = harness.results();
        assertOutcome(results, "Assertion fails", "failed", "expected 1 but was 2",
                "passed", "failed", "skipped");
        assertOutcome(results, "Code breaks", "broken", "connection refused", "broken");
        assertOutcome(results, "Pending step", "skipped", "not implemented", "skipped");
        assertOutcome(results, "Undefined step", "broken", "\"Given a step nobody defined\"",
                "broken", "skipped");
        assertOutcome(results, "Ambiguous step", "broken", null, "broken");
        assertOutcome(results, "Assumption skips", "skipped", "not on this environment", "skipped");

        Map<String, Object> before = byName(results, "Before hook fails");
        assertEquals("broken", before.get("outcome"));
        Map<String, Object> hook = list(before.get("setup_results")).get(0);
        assertEquals("Hooks.failBefore", hook.get("title"));
        assertEquals("broken", hook.get("outcome"));
        assertEquals(List.of("skipped"), outcomes(before.get("step_results")));

        Map<String, Object> after = byName(results, "After hook fails");
        assertEquals("failed", after.get("outcome"));
        assertEquals("failed", list(after.get("teardown_results")).get(0).get("outcome"));
        assertEquals(List.of("passed"), outcomes(after.get("step_results")));
        Map<String, Object> afterDef = byName(harness.defs(), "After hook fails");
        assertEquals(Arrays.asList("step", "after"), kinds(afterDef.get("steps")));

        Map<String, Object> beforeStep = byName(results, "BeforeStep hook fails");
        assertEquals("broken", beforeStep.get("outcome"));
        Map<String, Object> skippedStep = list(beforeStep.get("step_results")).get(0);
        assertEquals("skipped", skippedStep.get("outcome"));
        assertTrue(String.valueOf(skippedStep.get("message")).contains("before step hook broke"));

        Map<String, Object> afterStep = byName(results, "AfterStep hook fails");
        assertEquals("failed", afterStep.get("outcome"));
        Map<String, Object> failedStep = list(afterStep.get("step_results")).get(0);
        assertEquals("failed", failedStep.get("outcome"));
        assertTrue(String.valueOf(failedStep.get("message")).contains("after step check failed"));
    }

    @Test
    void attachmentsLogsAndNestedStepsLandInTheirStep() {
        Harness.platform(Map.of(), selectClasspathResource("features/extras"));

        Map<String, Object> result = byName(harness.results(), "Attachments, logs and nested steps");
        List<Map<String, Object>> setup = list(result.get("setup_results"));
        assertEquals(Arrays.asList("StatusSteps.remember", "Hooks.openBrowser"), titles(setup));
        assertEquals("browser opened", setup.get(1).get("message"));

        List<Map<String, Object>> steps = list(result.get("step_results"));
        Map<String, Object> attaching = steps.get(0);
        assertEquals("step log line", attaching.get("message"));
        assertEquals(2, list(attaching.get("attachments")).size());

        Map<String, Object> nested = steps.get(1);
        assertEquals(Arrays.asList("open the page", "check the title"), titles(nested.get("steps")));
        assertEquals("page opened", list(nested.get("steps")).get(0).get("message"));
        assertEquals(1, list(list(nested.get("steps")).get(1).get("attachments")).size());

        Map<String, Object> teardown = list(result.get("teardown_results")).get(0);
        assertEquals("Hooks.closeBrowser", teardown.get("title"));
        assertEquals(1, list(teardown.get("attachments")).size());

        Map<String, Object> def = byName(harness.defs(), "Attachments, logs and nested steps");
        assertEquals("[77]", String.valueOf(def.get("case_ids")));
        assertEquals(Arrays.asList("before", "before", "step", "step", "after"), kinds(def.get("steps")));
    }

    @Test
    void longNamesAndStepsAreClippedToTheServerLimits() {
        Harness.platform(Map.of(), selectClasspathResource("features/extras"));

        Map<String, Object> def = byName(harness.defs(), "Long <value>");
        assertEquals("Given the text <value>", titles(def.get("steps")).get(0));
        Map<String, Object> result = harness.results().stream()
                .filter(r -> String.valueOf(r.get("name")).startsWith("Long x"))
                .findFirst().orElseThrow();
        assertEquals(255, String.valueOf(result.get("name")).length());
        assertEquals(500, String.valueOf(list(result.get("step_results")).get(0).get("title")).length());
        assertEquals("passed", result.get("outcome"));
    }

    @Test
    void parallelScenariosKeepTheirOwnSteps() {
        Map<String, String> parallel = new HashMap<>();
        parallel.put("cucumber.execution.parallel.enabled", "true");
        parallel.put("cucumber.execution.parallel.config.strategy", "fixed");
        parallel.put("cucumber.execution.parallel.config.fixed.parallelism", "4");
        Harness.platform(parallel, selectClasspathResource("features/parallel"));

        List<Map<String, Object>> results = harness.results();
        assertEquals(6, results.size());
        for (int i = 1; i <= 6; i++) {
            Map<String, Object> result = byName(results, "Parallel " + i);
            assertEquals(Arrays.asList("Given parallel step " + i + ".1", "When parallel step " + i + ".2",
                    "Then parallel step " + i + ".3"), titles(result.get("step_results")));
        }
        long threads = Executed.LOG.stream().map(e -> e.substring(e.indexOf(" on ") + 4))
                .distinct().count();
        assertTrue(threads > 1, "scenarios ran on " + threads + " thread(s)");
    }

    @Test
    void twoSuitesInOneJvmBothReport() {
        Harness.run(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(BankSuite.class), selectClass(StatusesSuite.class)).build());

        List<Object> names = Harness.names(harness.results());
        assertTrue(names.contains("Simple transfer"), names.toString());
        assertTrue(names.contains("Assertion fails"), names.toString());
        assertEquals(1, harness.requests("POST", "/test-runs").size(), "one DoQA run per JVM");
    }

    @Test
    void importRealtimeSendsEveryScenarioAsItFinishes() {
        harness.configure(Map.of("doqa.adapterMode", "2", "doqa.importRealtime", "true"));
        Harness.platform(Map.of(), selectClasspathResource("features/bank"));
        assertEquals(7, harness.requests("POST", "/results").size());
        assertEquals(7, harness.results().size());
    }

    @Test
    void fileAndClasspathSelectionGiveTheSameKeys() {
        Harness.platform(Map.of(), selectClasspathResource("features/bank"));
        Set<Object> viaClasspath = ids(harness.results());
        harness.recorded.clear();
        harness.configure(Map.of("doqa.adapterMode", "2"));
        Harness.platform(Map.of(), selectDirectory("src/test/resources/features/bank"));
        assertEquals(viaClasspath, ids(harness.results()));
    }

    @Test
    void namingStrategiesDoNotChangeTheKeys() {
        Set<Object> reference = null;
        for (Map<String, String> strategy : Keys.namingStrategies()) {
            harness.recorded.clear();
            harness.configure(Map.of("doqa.adapterMode", "2"));
            Harness.platform(strategy, selectClasspathResource("features/bank"));
            Set<Object> ids = ids(harness.results());
            assertFalse(ids.isEmpty());
            if (reference == null) {
                reference = ids;
            } else {
                assertEquals(reference, ids, "keys under " + strategy);
            }
        }
    }

    private static void assertOutcome(List<Map<String, Object>> results, String name, String outcome,
                                      String messagePart, String... stepOutcomes) {
        Map<String, Object> result = byName(results, name);
        assertEquals(outcome, result.get("outcome"), name);
        if (messagePart != null) {
            assertTrue(String.valueOf(result.get("message")).contains(messagePart),
                    name + ": " + result.get("message"));
        } else {
            assertTrue(result.get("message") != null, name + " has a message");
        }
        assertEquals(Arrays.asList(stepOutcomes), outcomes(result.get("step_results")), name);
    }

    static Set<Object> ids(List<Map<String, Object>> results) {
        return results.stream().map(r -> r.get("external_id")).collect(Collectors.toCollection(TreeSet::new));
    }

    static List<Object> titles(Object steps) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : list(steps)) {
            out.add(s.get("title"));
        }
        return out;
    }

    private static List<Object> kinds(Object steps) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : list(steps)) {
            out.add(s.get("kind"));
        }
        return out;
    }

    private static List<Object> outcomes(Object steps) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : list(steps)) {
            out.add(s.get("outcome"));
        }
        return out;
    }
}
