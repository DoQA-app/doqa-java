package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import app.doqa.core.DoqaSession;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

class DegradationTest {

    private Harness harness;

    @BeforeEach
    void setUp() {
        harness = new Harness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void unreachableRunFallsBackToFilesWithTheSameBuildOutcome(@TempDir Path dir) throws IOException {
        harness.configure(Map.of("doqa.reporting", "off"));
        long failuresWithoutDoqa = Harness.platform(Map.of(),
                selectClasspathResource("features/statuses")).getTotalFailureCount();

        harness.testRunStatus = 500;
        harness.configure(Map.of("doqa.adapterMode", "2", "doqa.resultsDir", dir.toString()));
        TestExecutionSummary summary = Harness.platform(Map.of(),
                selectClasspathResource("features/statuses"));

        assertEquals(failuresWithoutDoqa, summary.getTotalFailureCount());
        assertTrue(harness.requests("POST", "/results").isEmpty());
        List<String> results = resultFiles(dir);
        assertEquals(10, results.size(), results.toString());
        String marker = new String(Files.readAllBytes(dir.resolve("doqa-reporting.properties")),
                StandardCharsets.UTF_8);
        assertTrue(marker.contains("degradedFrom=api"), marker);
    }

    @Test
    void fileSinkLabelsScenariosAsCucumber(@TempDir Path dir) throws IOException {
        harness.configure(Map.of("doqa.reporting", "files", "doqa.resultsDir", dir.toString()));
        Harness.platform(Map.of(), selectClasspathResource("features/bank"));

        List<String> results = resultFiles(dir);
        assertEquals(7, results.size());
        for (String json : results) {
            assertTrue(json.contains("{\"name\":\"framework\",\"value\":\"cucumber\"}"), json);
        }
        assertTrue(results.stream().anyMatch(r -> r.contains("\"transfer-alice\"")));
    }

    @Test
    void failureInsideThePluginDoesNotFailTheScenarios() {
        harness.configure(Map.of("doqa.adapterMode", "2"));
        DoqaSession.setClientFactory(config -> {
            throw new LinkageError("broken client");
        });
        TestExecutionSummary summary = Harness.platform(Map.of(),
                selectClasspathResource("features/bank"));
        assertEquals(7, summary.getTestsSucceededCount());
        assertEquals(0, summary.getTotalFailureCount());
    }

    @Test
    void missingPluginLineIsReportedOnce() throws Exception {
        harness.configure(Map.of("doqa.adapterMode", "2"));
        Harness.forgetPluginCreated();
        Field warned = DoqaCucumberExecutionListener.class.getDeclaredField("WARNED_MISSING_PLUGIN");
        warned.setAccessible(true);
        ((AtomicBoolean) warned.get(null)).set(false);

        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger(DoqaCucumberExecutionListener.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            TestExecutionSummary summary = Harness.run(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClasspathResource("features/bank"))
                    .configurationParameter("cucumber.glue", Harness.GLUE)
                    .build());
            assertEquals(7, summary.getTestsSucceededCount());
        } finally {
            logger.removeHandler(handler);
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("cucumber.plugin=app.doqa.cucumber.DoqaCucumberPlugin"));
        assertTrue(harness.requests("POST", "/results").isEmpty());
    }

    private static List<String> resultFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> paths = files.filter(p -> p.getFileName().toString().endsWith("-result.json"))
                    .collect(Collectors.toList());
            List<String> out = new ArrayList<>();
            for (Path p : paths) {
                out.add(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
            return out;
        }
    }
}
