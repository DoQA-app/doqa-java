package app.doqa.junit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.doqa.client.ApiClient;
import app.doqa.client.Json;
import app.doqa.client.Transport;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.DoqaSession;
import app.doqa.core.SignatureHash;
import app.doqa.cuke4.JointCucumberRunner;
import app.doqa.cuke4.NoPluginCucumberRunner;
import app.doqa.e2e4.regression.ClassAllureScenario;
import app.doqa.e2e4.regression.ClassIdScenario;
import app.doqa.e2e4.regression.KeysScenario;
import app.doqa.e2e4.regression.ParamKeysScenario;
import app.doqa.e2e4.regression.PlainParamScenario;
import app.doqa.testkit.FakeBackend;
import app.doqa.testkit.PayloadSnapshot;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.RunNotifier;

public class CucumberJointTest {

    private static final String FEATURE = "cuke4/joint.feature";
    private static final String CUCUMBER_ID = "\"external_id\":\"cucumber:";

    @Rule
    public TemporaryFolder dir = new TemporaryFolder();

    private final AtomicInteger testRuns = new AtomicInteger();

    @Before
    public void setUp() {
        forgetCucumberPlugin();
        System.setProperty("cucumber.publish.quiet", "true");
    }

    @After
    public void cleanup() {
        FakeBackend.uninstall();
        AdapterState.resetAll();
        forgetCucumberPlugin();
        System.clearProperty("cucumber.publish.quiet");
    }

    @Test
    public void scenariosGoThroughThePluginOnceAndUnitTestsKeepTheirPayload() {
        FakeBackend backend = install(dir.getRoot().toPath());
        run(KeysScenario.class, ParamKeysScenario.class, PlainParamScenario.class,
                ClassIdScenario.class, ClassAllureScenario.class, JointCucumberRunner.class);

        List<String> unit = new ArrayList<>();
        List<String> scenarios = new ArrayList<>();
        for (String line : PayloadSnapshot.api(backend)) {
            (line.contains(CUCUMBER_ID) ? scenarios : unit).add(line);
        }
        PayloadSnapshot.assertGolden("junit4-api", unit);

        List<Map<String, Object>> results = results(backend);
        assertEquals("one DoQA run for unit tests and scenarios", 1, testRuns.get());
        assertEquals(1, named(results, "Deposit").size());
        assertEquals(1, named(results, "Withdraw 3").size());
        assertEquals(1, named(results, "Withdraw 4").size());
        assertEquals(cucumberId("Deposit"), named(results, "Deposit").get(0).get("external_id"));
        assertEquals(cucumberId("Withdraw <amount>"),
                named(results, "Withdraw 4").get(0).get("external_id"));
        assertEquals(String.join("\n", scenarios), 5, scenarios.size());
    }

    @Test
    public void fileSinkLabelsEachTestWithItsOwnFramework() {
        Path root = dir.getRoot().toPath();
        FakeBackend.installFiles(root);
        runLikeSurefire(KeysScenario.class, ParamKeysScenario.class, PlainParamScenario.class,
                ClassIdScenario.class, ClassAllureScenario.class, JointCucumberRunner.class);

        List<String> unit = new ArrayList<>();
        List<String> scenarios = new ArrayList<>();
        for (String line : PayloadSnapshot.files(root)) {
            (line.contains("framework=cucumber ") ? scenarios : unit).add(line);
        }
        PayloadSnapshot.assertGolden("junit4-files", unit);
        assertEquals(String.join("\n", scenarios), 3, scenarios.size());
        for (String line : scenarios) {
            assertTrue(line, line.contains(" id=cucumber:"));
        }
    }

    @Test
    public void withoutThePluginLineScenariosAreReportedAsBefore() {
        FakeBackend backend = install(dir.getRoot().toPath());
        run(NoPluginCucumberRunner.class);

        List<Map<String, Object>> results = results(backend);
        assertEquals(results.toString(), 3, results.size());
        for (Map<String, Object> result : results) {
            assertTrue(result.toString(),
                    String.valueOf(result.get("external_id")).startsWith("junit4:"));
        }
        assertEquals(1, named(results, "Deposit").size());
    }

    private FakeBackend install(Path root) {
        FakeBackend backend = new FakeBackend().install(root);
        Transport counting = request -> {
            if ("POST".equals(request.method) && request.url.endsWith("/test-runs")) {
                testRuns.incrementAndGet();
            }
            return backend.send(request);
        };
        DoqaSession.setClientFactory(config -> new ApiClient(config, counting, 1, 0));
        return backend;
    }

    private static void run(Class<?>... classes) {
        AdapterState.resetAll();
        JUnitCore core = new JUnitCore();
        core.addListener(new DoqaRunListener());
        core.run(classes);
    }

    // surefire's JUnit 4 provider: the run start names no classes, each class announces itself via its suite event
    private static void runLikeSurefire(Class<?>... classes) {
        AdapterState.resetAll();
        RunNotifier notifier = new RunNotifier();
        notifier.addListener(new DoqaRunListener());
        notifier.fireTestRunStarted(Description.createSuiteDescription("UNDETERMINED_TESTS"));
        for (Class<?> testClass : classes) {
            Request.aClass(testClass).getRunner().run(notifier);
        }
        notifier.fireTestRunFinished(new Result());
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
