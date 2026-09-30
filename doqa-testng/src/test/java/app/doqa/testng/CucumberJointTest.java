package app.doqa.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import app.doqa.client.ApiClient;
import app.doqa.client.DoqaConfig;
import app.doqa.client.Json;
import app.doqa.client.Transport;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.DoqaSession;
import app.doqa.core.SignatureHash;
import app.doqa.cukeng.JointCucumberRunner;
import app.doqa.cukeng.NoPluginCucumberRunner;
import app.doqa.cukeng.steps.AccountSteps;
import app.doqa.e2eng.regression.ClassAllureScenario;
import app.doqa.e2eng.regression.ClassIdScenario;
import app.doqa.e2eng.regression.KeysScenario;
import app.doqa.testkit.FakeBackend;
import app.doqa.testkit.PayloadSnapshot;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.TestNG;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class CucumberJointTest {

    private static final String FEATURE = "cukeng/joint.feature";
    private static final String CUCUMBER_ID = "\"external_id\":\"cucumber:";
    private static final String RUNNER_TEST = "runnerSmoke";

    private final AtomicInteger testRuns = new AtomicInteger();
    private volatile String selection = "{\"autotests\":[]}";

    @BeforeMethod
    public void setUp() {
        forgetCucumberPlugin();
        DoqaTestNgListener.resetState();
        AccountSteps.EXECUTED.clear();
        System.setProperty("cucumber.publish.quiet", "true");
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() {
        FakeBackend.uninstall();
        DoqaTestNgListener.resetState();
        forgetCucumberPlugin();
        System.clearProperty("cucumber.publish.quiet");
    }

    @Test
    public void scenariosGoThroughThePluginOnceAndUnitTestsKeepTheirPayload() throws IOException {
        FakeBackend backend = install(apiConfig().build());
        run(KeysScenario.class, ClassIdScenario.class, ClassAllureScenario.class,
                JointCucumberRunner.class);

        List<String> unit = new ArrayList<>();
        List<String> scenarios = new ArrayList<>();
        for (String line : PayloadSnapshot.api(backend)) {
            if (line.contains(CUCUMBER_ID)) {
                scenarios.add(line);
            } else if (!line.contains("\"name\":\"" + RUNNER_TEST + "\"")) {
                unit.add(line);
            }
        }
        PayloadSnapshot.assertGolden("testng-api", unit);

        List<Map<String, Object>> results = results(backend);
        assertEquals(testRuns.get(), 1, "one DoQA run for unit tests and scenarios");
        assertEquals(named(results, "Deposit").size(), 1);
        assertEquals(named(results, "Withdraw 3").size(), 1);
        assertEquals(named(results, "Withdraw 4").size(), 1);
        assertEquals(named(results, "Deposit").get(0).get("external_id"), cucumberId("Deposit"));
        assertEquals(named(results, "Withdraw 3").get(0).get("external_id"),
                cucumberId("Withdraw <amount>"));
        assertEquals(scenarios.size(), 5, String.join("\n", scenarios));

        // the runner's own fixtures start Cucumber, they are not fixtures of its tests
        List<Map<String, Object>> smoke = named(results, RUNNER_TEST);
        assertEquals(smoke.size(), 1);
        assertFalse(smoke.get(0).containsKey("setup_results"), smoke.toString());
        assertFalse(smoke.get(0).containsKey("teardown_results"), smoke.toString());
    }

    @Test
    public void selectiveRunExecutesTheSelectedScenarios() throws IOException {
        String deposit = cucumberId("Deposit");
        selection = "{\"autotests\":[{\"externalId\":\"ALLURE-777\"},{\"externalId\":\""
                + deposit + "\"}]}";
        FakeBackend backend = install(apiConfig().adapterMode(0).testRunId("77").build());
        run(KeysScenario.class, ClassAllureScenario.class, JointCucumberRunner.class);

        assertTrue(AccountSteps.EXECUTED.contains("deposit 5"), AccountSteps.EXECUTED.toString());
        Set<Object> reported = new TreeSet<>();
        for (Map<String, Object> result : results(backend)) {
            reported.add(result.get("external_id"));
        }
        assertEquals(reported, new TreeSet<>(List.of("ALLURE-777", deposit)));
        assertEquals(named(results(backend), "Deposit").size(), 1);
    }

    @Test
    public void withoutThePluginLineScenariosAreReportedAsBefore() throws IOException {
        FakeBackend backend = install(apiConfig().build());
        run(NoPluginCucumberRunner.class);

        List<Map<String, Object>> results = results(backend);
        assertEquals(results.size(), 3, results.toString());
        for (Map<String, Object> result : results) {
            assertEquals(result.get("name"), "Runs Cucumber Scenarios");
            assertTrue(String.valueOf(result.get("external_id")).startsWith("testng:"),
                    result.toString());
            assertTrue(String.valueOf(result.get("setup_results")).contains("setUpClass"),
                    result.toString());
        }
    }

    private static DoqaConfig.Builder apiConfig() throws IOException {
        return new DoqaConfig.Builder()
                .url("https://doqa.test").token("TOKEN").spaceId("31")
                .reporting(DoqaConfig.REPORTING_API)
                .resultsDir(Files.createTempDirectory("doqa-joint").toString());
    }

    private FakeBackend install(DoqaConfig config) {
        FakeBackend backend = new FakeBackend().install(config);
        Transport transport = request -> {
            if ("POST".equals(request.method) && request.url.endsWith("/test-runs")) {
                testRuns.incrementAndGet();
            }
            if ("GET".equals(request.method) && request.url.contains("/test-runs/77/autotests")) {
                return new Transport.Response(200, selection);
            }
            return backend.send(request);
        };
        DoqaSession.setClientFactory(c -> new ApiClient(c, transport, 1, 0));
        return backend;
    }

    private static void run(Class<?>... classes) {
        DoqaTestNgListener.resetState();
        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setOutputDirectory("target/testng-out");
        testng.setTestClasses(classes);
        testng.run();
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
        try {
            Field field = AdapterRuntime.class.getDeclaredField("cucumberPluginCreated");
            field.setAccessible(true);
            field.setBoolean(null, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
