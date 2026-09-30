package app.doqa.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.doqa.client.ApiClient;
import app.doqa.client.Json;
import app.doqa.client.Transport;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.DoqaSession;
import app.doqa.core.SignatureHash;
import app.doqa.cucumber.DoqaCucumberExecutionListener;
import app.doqa.cucumber.DoqaCucumberPlugin;
import app.doqa.e2e.regression.ClassAllureScenario;
import app.doqa.e2e.regression.ClassIdScenario;
import app.doqa.e2e.regression.KeysScenario;
import app.doqa.testkit.FakeBackend;
import app.doqa.testkit.PayloadSnapshot;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class CucumberJointTest {

    private static final String FEATURE = "cuke5/joint.feature";
    private static final String CUCUMBER_ID = "\"external_id\":\"cucumber:";

    private final AtomicInteger testRuns = new AtomicInteger();

    @BeforeEach
    void setUp() {
        forgetCucumberPlugin();
    }

    @AfterEach
    void cleanup() {
        FakeBackend.uninstall();
        forgetCucumberPlugin();
    }

    @Test
    void scenariosGoThroughThePluginOnceAndUnitTestsKeepTheirPayload(@TempDir Path dir) {
        FakeBackend backend = install(dir);
        launch(true);

        List<String> unit = new ArrayList<>();
        List<String> scenarios = new ArrayList<>();
        for (String line : PayloadSnapshot.api(backend)) {
            (line.contains(CUCUMBER_ID) ? scenarios : unit).add(line);
        }
        PayloadSnapshot.assertGolden("junit5-api", unit);

        List<Map<String, Object>> results = results(backend);
        assertEquals(1, testRuns.get(), "one DoQA run for unit tests and scenarios");
        assertEquals(1, named(results, "Deposit").size());
        assertEquals(1, named(results, "Withdraw 3").size());
        assertEquals(1, named(results, "Withdraw 4").size());
        assertEquals(cucumberId("Deposit"), named(results, "Deposit").get(0).get("external_id"));
        assertEquals(cucumberId("Withdraw <amount>"),
                named(results, "Withdraw 3").get(0).get("external_id"));
        assertEquals(5, scenarios.size(), String.join("\n", scenarios));
    }

    @Test
    void fileSinkLabelsEachTestWithItsOwnFramework(@TempDir Path dir) {
        FakeBackend.installFiles(dir);
        launch(true);

        List<String> unit = new ArrayList<>();
        List<String> scenarios = new ArrayList<>();
        for (String line : PayloadSnapshot.files(dir)) {
            (line.contains("framework=cucumber ") ? scenarios : unit).add(line);
        }
        PayloadSnapshot.assertGolden("junit5-files", unit);
        assertEquals(3, scenarios.size(), String.join("\n", scenarios));
        for (String line : scenarios) {
            assertTrue(line.contains(" id=cucumber:"), line);
        }
    }

    @Test
    void withoutThePluginLineScenariosAreReportedAsBefore(@TempDir Path dir) throws Exception {
        forgetMissingPluginWarning();
        List<String> warnings = new CopyOnWriteArrayList<>();
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
        FakeBackend backend = install(dir);
        try {
            launch(false);
        } finally {
            logger.removeHandler(handler);
        }

        List<Map<String, Object>> results = results(backend);
        List<Map<String, Object>> deposit = named(results, "Deposit");
        assertEquals(1, deposit.size());
        assertEquals(SignatureHash.fallbackExternalId("junit5", "#Deposit"),
                deposit.get(0).get("external_id"));
        assertEquals(0, results.stream()
                .filter(r -> String.valueOf(r.get("external_id")).startsWith("cucumber:")).count());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("cucumber.plugin="
                + DoqaCucumberPlugin.class.getName())), warnings.toString());
    }

    private FakeBackend install(Path dir) {
        FakeBackend backend = new FakeBackend().install(dir);
        Transport counting = request -> {
            if ("POST".equals(request.method) && request.url.endsWith("/test-runs")) {
                testRuns.incrementAndGet();
            }
            return backend.send(request);
        };
        DoqaSession.setClientFactory(config -> new ApiClient(config, counting, 1, 0));
        return backend;
    }

    private static void launch(boolean withPlugin) {
        LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request()
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("cucumber.glue", "app.doqa.cuke5.steps")
                .configurationParameter("cucumber.publish.quiet", "true")
                .selectors(DiscoverySelectors.selectClass(KeysScenario.class),
                        DiscoverySelectors.selectClass(ClassIdScenario.class),
                        DiscoverySelectors.selectClass(ClassAllureScenario.class),
                        DiscoverySelectors.selectClasspathResource(FEATURE));
        if (withPlugin) {
            request.configurationParameter("cucumber.plugin", DoqaCucumberPlugin.class.getName());
        }
        LauncherFactory.create().execute(request.build());
    }

    private static String cucumberId(String scenario) {
        return SignatureHash.fallbackExternalId("cucumber", FEATURE + "#" + scenario);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(FakeBackend backend) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String body : backend.results) {
            for (Object result : (List<Object>) Json.parseObject(body).get("results")) {
                out.add((Map<String, Object>) result);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> named(List<Map<String, Object>> items, String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (name.equals(item.get("name"))) {
                out.add(item);
            }
        }
        return out;
    }

    // the plugin flag is JVM-wide: every test starts as a JVM where no plugin was created
    private static void forgetCucumberPlugin() {
        setStatic(AdapterRuntime.class, "cucumberPluginCreated", false);
    }

    private static void forgetMissingPluginWarning() throws ReflectiveOperationException {
        Field field = DoqaCucumberExecutionListener.class.getDeclaredField("WARNED_MISSING_PLUGIN");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(null)).set(false);
    }

    private static void setStatic(Class<?> owner, String name, boolean value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
