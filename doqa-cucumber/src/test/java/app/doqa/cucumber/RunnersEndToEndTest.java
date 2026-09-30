package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import app.doqa.client.Json;
import app.doqa.cucumber.e2e.AllFeaturesJUnit4Runner;
import app.doqa.cucumber.e2e.AllFeaturesTestNGRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunnersEndToEndTest {

    private static final Set<String> VOLATILE = Set.of("started_on", "completed_on", "duration_ms",
            "traces");

    private Harness harness;

    @BeforeEach
    void setUp() {
        harness = new Harness().configure(Map.of("doqa.adapterMode", "2"));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void junit4RunnerReportsLikeThePlatform() {
        List<String> platform = platformSnapshot();
        Harness.junit4(AllFeaturesJUnit4Runner.class);
        assertSameLines(platform, snapshot());
    }

    @Test
    void testngRunnerReportsLikeThePlatform() {
        List<String> platform = platformSnapshot();
        Harness.testng(AllFeaturesTestNGRunner.class);
        assertSameLines(platform, snapshot());
    }

    private List<String> platformSnapshot() {
        Harness.platform(Map.of(), selectClasspathResource("features/bank"),
                selectClasspathResource("features/ru"), selectClasspathResource("features/statuses"),
                selectClasspathResource("features/extras"));
        List<String> lines = snapshot();
        assertFalse(lines.isEmpty());
        harness.recorded.clear();
        harness.configure(Map.of("doqa.adapterMode", "2"));
        return lines;
    }

    private List<String> snapshot() {
        Set<String> lines = new TreeSet<>();
        for (Map<String, Object> def : harness.defs()) {
            lines.add("def " + Json.write(canonical(def)));
        }
        List<String> results = new ArrayList<>();
        for (Map<String, Object> result : harness.results()) {
            results.add("result " + Json.write(canonical(result)));
        }
        results.sort(null);
        List<String> out = new ArrayList<>(lines);
        out.addAll(results);
        return out;
    }

    private static void assertSameLines(List<String> expected, List<String> actual) {
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(actual);
        List<String> extra = new ArrayList<>(actual);
        extra.removeAll(expected);
        assertEquals(List.of(), missing, "reported by the platform only (extra: " + extra + ")");
        assertEquals(List.of(), extra, "reported by the runner only");
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!VOLATILE.contains(String.valueOf(e.getKey()))) {
                    sorted.put(String.valueOf(e.getKey()), canonical(e.getValue()));
                }
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) value) {
                out.add(canonical(o));
            }
            return out;
        }
        return value;
    }
}
