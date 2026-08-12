package app.doqa.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import app.doqa.client.Json;
import app.doqa.core.DoqaSession;
import app.doqa.e2eng.BrokenClassFixtureScenario;
import app.doqa.e2eng.BrokenFixtureScenario;
import app.doqa.e2eng.DataProviderScenario;
import app.doqa.e2eng.DemoLoginScenario;
import app.doqa.e2eng.OrderScenario;
import app.doqa.e2eng.ParallelScenario;
import app.doqa.e2eng.RetryScenario;
import app.doqa.e2eng.SelectBaseScenario;
import app.doqa.e2eng.SelectGroupScenario;
import app.doqa.e2eng.SelectScenario;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.testng.TestNG;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

/**
 * End-to-end tests: a nested TestNG run executes the demo suites with the adapter attached the way a
 * host build gets it - the reporting listener and the method interceptor come from
 * {@code META-INF/services/org.testng.ITestNGListener} through the {@code ServiceLoader}, nothing is
 * registered by hand - plus AspectJ LTW for {@code @Step} (surefire runs with
 * {@code -javaagent:aspectjweaver}), config via system-property resolution and the real
 * {@code HttpClientTransport}, all against a local {@link HttpServer} faking the Autotest API.
 * Captured upsert/results/attachments/test-runs payloads are asserted against the API contract.
 *
 * <p>Nested runs share one JVM with the OUTER surefire run, whose own session is disabled
 * ({@code doqa.reporting=off}) and whose listener instances keep static state. Every scenario
 * therefore resets the adapter's run state and the session BEFORE configuring its own, and the
 * machine environment is isolated per test ({@code DoqaSession.setEnvOverride} hides real
 * {@code DOQA_*} variables, {@code doqa.config} points at a non-existent file), so local
 * {@code doqa.properties} or exported credentials never leak into (or out of) these runs.
 */
public class SuiteRunEndToEndTest {

    /** One recorded HTTP exchange. */
    static final class Recorded {
        final String method;
        final String path;
        final String body;

        Recorded(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }

    private static final String DEFAULT_SELECTIVE_RESPONSE =
            "{\"autotests\":[{\"externalId\":\"E2ENG-SEL-1\"}]}";
    /** TestNG's own reporters are off, but it still wants a directory to point them at. */
    private static final String OUTPUT_DIR = "target/testng-out";

    private HttpServer server;
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private final List<String> propsSet = new ArrayList<>();
    /** Per-test override of the GET /autotests selective (ordered) plan response. */
    private volatile String selectiveResponse = DEFAULT_SELECTIVE_RESPONSE;

    @BeforeMethod
    public void startFakeBackend() throws IOException {
        DoqaTestNgListener.resetState();
        DoqaSession.reset();
        DoqaSession.setEnvOverride(Collections.emptyMap());
        setProp("doqa.config", "target/no-doqa.properties");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            recorded.add(new Recorded(exchange.getRequestMethod(),
                    path + (query == null ? "" : "?" + query),
                    new String(bodyBytes, StandardCharsets.UTF_8)));
            byte[] resp = respond(exchange.getRequestMethod(), path)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    private String respond(String method, String path) {
        if ("POST".equals(method) && path.endsWith("/test-runs")) {
            return "{\"runId\":4242}";
        }
        if ("GET".equals(method) && path.endsWith("/autotests")) {
            return selectiveResponse;
        }
        if (path.endsWith("/upsert")) {
            return "{\"map\":{}}";
        }
        if (path.endsWith("/attachments")) {
            return "{\"mediaFileId\":555}";
        }
        if (path.endsWith("/results")) {
            return "{\"accepted\":1,\"elementIds\":[]}";
        }
        return "{}";
    }

    @AfterMethod
    public void cleanup() {
        for (String p : propsSet) {
            System.clearProperty(p);
        }
        propsSet.clear();
        DoqaTestNgListener.resetState();
        DoqaSession.reset();
        DoqaSession.setClientFactory(null);
        DoqaSession.setConfigOverride(null);
        DoqaSession.setEnvOverride(null);
        if (server != null) {
            server.stop(0);
        }
        recorded.clear();
        selectiveResponse = DEFAULT_SELECTIVE_RESPONSE;
        OrderScenario.EXECUTED.clear();
        RetryScenario.attempts = 0;
        DataProviderScenario.placeholderExecuted = 0;
        DataProviderScenario.collapsedExecuted = 0;
        BrokenFixtureScenario.executed = 0;
        BrokenClassFixtureScenario.executed = 0;
        SelectBaseScenario.baseExecuted = 0;
        SelectScenario.selectedExecuted = 0;
        SelectScenario.deselectedExecuted = 0;
        SelectScenario.dependentExecuted = 0;
        SelectGroupScenario.groupExecuted = 0;
        SelectGroupScenario.dependentExecuted = 0;
    }

    private void configure(Map<String, String> extra) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("doqa.url", "http://127.0.0.1:" + server.getAddress().getPort());
        props.put("doqa.token", "E2E-TOKEN");
        props.put("doqa.spaceId", "31");
        props.put("doqa.reporting", "api");
        props.putAll(extra);
        for (Map.Entry<String, String> e : props.entrySet()) {
            setProp(e.getKey(), e.getValue());
        }
        DoqaSession.reset();
    }

    /**
     * Nested run: neither listener is added by hand - both arrive through the ServiceLoader, exactly
     * as in a host build, so a double registration (and a double intercept) cannot be papered over
     * here. The adapter's process-wide run state is wiped first: the outer surefire run already
     * marked a run active, and {@code beginRun} deliberately keeps the state of an outer run.
     */
    private static void run(Class<?>... testClasses) {
        DoqaTestNgListener.resetState();
        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setOutputDirectory(OUTPUT_DIR);
        testng.setTestClasses(testClasses);
        testng.run();
    }

    /** Nested run of a hand-built suite - the only way to set {@code parallel} / {@code <test>}s. */
    private static void run(XmlSuite suite) {
        DoqaTestNgListener.resetState();
        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setOutputDirectory(OUTPUT_DIR);
        testng.setXmlSuites(Collections.singletonList(suite));
        testng.run();
    }

    // ------------------------------------------------------------------ helpers
    private Recorded only(String method, String pathSuffix) {
        List<Recorded> hits = all(method, pathSuffix);
        assertEquals(hits.size(), 1, method + " " + pathSuffix + " count");
        return hits.get(0);
    }

    private List<Recorded> all(String method, String pathSuffix) {
        List<Recorded> hits = new ArrayList<>();
        for (Recorded r : recorded) {
            String pathOnly = r.path.contains("?") ? r.path.substring(0, r.path.indexOf('?')) : r.path;
            if (r.method.equals(method) && pathOnly.endsWith(pathSuffix)) {
                hits.add(r);
            }
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object e : (List<Object>) o) {
                if (e instanceof Map) {
                    out.add((Map<String, Object>) e);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> byExternalId(List<Map<String, Object>> items, String id) {
        for (Map<String, Object> m : items) {
            if (id.equals(m.get("external_id"))) {
                return m;
            }
        }
        return null;
    }

    private static Map<String, Object> byName(List<Map<String, Object>> items, String name) {
        for (Map<String, Object> m : items) {
            if (name.equals(m.get("name"))) {
                return m;
            }
        }
        return null;
    }

    private static List<String> titles(Object stepResults) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> step : maps(stepResults)) {
            out.add(String.valueOf(step.get("title")));
        }
        return out;
    }

    private static List<String> outcomes(List<Map<String, Object>> results) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : results) {
            out.add(String.valueOf(r.get("outcome")));
        }
        return out;
    }

    private List<Map<String, Object>> defs() {
        return maps(Json.parseObject(only("POST", "/api/autotests/upsert").body).get("autotests"));
    }

    private List<Map<String, Object>> results() {
        return maps(Json.parseObject(only("POST", "/api/autotests/results").body).get("results"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put(keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    // ------------------------------------------------------------------ full contract
    @Test
    public void mode2FullContractThroughNestedRun() {
        configure(map("doqa.adapterMode", "2", "doqa.testRunName", "e2eng run"));
        run(DemoLoginScenario.class);

        // ---- run created (mode 2) with auth + name ----
        Map<String, Object> runBody =
                Json.parseObject(only("POST", "/api/autotests/test-runs").body);
        assertEquals(runBody.get("token"), "E2E-TOKEN");
        assertEquals(runBody.get("space_id"), "31");
        assertEquals(runBody.get("name"), "e2eng run");

        // ---- attachments uploaded as multipart before results (test shot + @BeforeClass log) ----
        List<Recorded> attachments = all("POST", "/api/autotests/attachments");
        assertEquals(attachments.size(), 2, "test screenshot + in-memory fixture attachment");
        Recorded screenshot = null;
        boolean fixtureAttachment = false;
        for (Recorded a : attachments) {
            if (a.body.contains("doqa-e2eng-shot")) {
                screenshot = a;
            }
            fixtureAttachment |= a.body.contains("init.log");
        }
        assertNotNull(screenshot, "screenshot upload present");
        assertTrue(screenshot.body.contains("name=\"file\""), "file part present");
        assertTrue(screenshot.body.contains("E2E-TOKEN"), "token field present");
        assertTrue(screenshot.body.contains("Content-Type: image/png"),
                "content type inferred from the file name");
        assertTrue(fixtureAttachment, "in-memory @BeforeClass attachment uploaded");

        // ---- batched upsert: full def model ----
        List<Map<String, Object>> defs = defs();
        assertEquals(defs.size(), 6, "4 executed + 1 disabled + 1 skipped by dependency");

        Map<String, Object> login = byExternalId(defs, "E2ENG-LOGIN-1");
        assertNotNull(login, "explicit externalId def present");
        assertEquals(login.get("title"), "Login works");
        assertEquals(login.get("namespace"), "app.doqa.e2eng");
        assertEquals(login.get("classname"), "DemoLoginScenario");
        assertEquals(login.get("runner_method"), "loginHappyPath");
        assertTrue(list(login.get("labels")).containsAll(Arrays.asList("e2eng-class", "smoke")),
                "class+method labels merged");
        assertTrue(list(login.get("tags")).contains("native-group"),
                "native TestNG groups land in tags");
        Map<String, Object> link = maps(login.get("links")).get(0);
        assertEquals(link.get("type"), "defect");
        assertEquals(link.get("url"), "http://tracker/BUG-1");
        assertEquals(login.get("case_ids"), Arrays.asList(901L));

        // def steps: @BeforeMethod -> before, body steps -> step (with the aspect-woven children),
        // @AfterMethod -> after (@BeforeClass/@AfterClass are execution fixtures, not def steps)
        List<Map<String, Object>> steps = maps(login.get("steps"));
        assertEquals(steps.size(), 3);
        assertEquals(steps.get(0).get("kind"), "before");
        assertEquals(steps.get(0).get("title"), "setUp");
        Map<String, Object> callStep = steps.get(1);
        assertEquals(callStep.get("kind"), "step");
        assertEquals(callStep.get("title"), "open login page");
        List<Map<String, Object>> nested = maps(callStep.get("steps"));
        assertEquals(nested.get(0).get("title"), "annotated helper",
                "AspectJ LTW must weave the @Step helper");
        assertEquals(nested.get(1).get("title"), "open home page",
                "{param} placeholder resolved from the method argument");
        assertEquals(steps.get(2).get("kind"), "after");
        assertEquals(steps.get(2).get("title"), "tearDown");

        // hash-fallback attribution for the un-annotated tests
        Map<String, Object> failingDef = byName(defs, "assertionFails");
        assertNotNull(failingDef);
        assertTrue(String.valueOf(failingDef.get("external_id")).startsWith("testng:"),
                "signature-hash fallback externalId");

        // ---- batched results: outcomes, params, steps, fixtures, attachments ----
        Recorded resultsPost = only("POST", "/api/autotests/results");
        Map<String, Object> resultsBody = Json.parseObject(resultsPost.body);
        assertEquals(String.valueOf(resultsBody.get("test_run_id")), "4242");
        List<Map<String, Object>> res = maps(resultsBody.get("results"));
        assertEquals(res.size(), 6);
        Set<String> reportedIds = new LinkedHashSet<>();
        for (Map<String, Object> r : res) {
            assertTrue(reportedIds.add(String.valueOf(r.get("external_id"))),
                    "every test is reported exactly once: " + r.get("external_id"));
        }

        Map<String, Object> loginRes = byExternalId(res, "E2ENG-LOGIN-1");
        assertEquals(loginRes.get("outcome"), "passed");
        assertTrue(String.valueOf(loginRes.get("message")).contains("hello from runtime"));
        List<Map<String, Object>> params = maps(loginRes.get("parameters"));
        assertEquals(params.get(0).get("name"), "browser");
        assertEquals(params.get(0).get("value"), "chrome");
        assertEquals(String.valueOf(maps(loginRes.get("attachments")).get(0).get("media_file_id")),
                "555");
        assertNotNull(loginRes.get("started_on"));
        assertNotNull(loginRes.get("duration_ms"));

        // class fixtures (batch flush): @BeforeClass prepended to setup, @AfterClass appended to teardown
        List<Map<String, Object>> setup = maps(loginRes.get("setup_results"));
        assertEquals(setup.get(0).get("title"), "beforeClassInit", "@BeforeClass first in setup");
        assertEquals(maps(setup.get(0).get("steps")).get(0).get("title"), "prepare db",
                "Doqa.step inside @BeforeClass nests under the fixture node");
        assertEquals(maps(setup.get(0).get("attachments")).size(), 1,
                "Doqa.addAttachment inside @BeforeClass survives");
        assertEquals(setup.get(1).get("title"), "setUp");

        // the point of the deferred send: @AfterMethod runs AFTER the final ITestListener callback
        List<Map<String, Object>> teardown = maps(loginRes.get("teardown_results"));
        assertEquals(teardown.get(0).get("title"), "tearDown");
        assertEquals(titles(teardown.get(0).get("steps")), Arrays.asList("close browser"),
                "a step taken in @AfterMethod reaches the result");
        assertEquals(teardown.get(teardown.size() - 1).get("title"), "afterClassCleanup",
                "@AfterClass last in teardown");

        Map<String, Object> stepRes = maps(loginRes.get("step_results")).get(0);
        assertEquals(stepRes.get("title"), "open login page");
        assertEquals(stepRes.get("outcome"), "passed");
        assertEquals(titles(stepRes.get("steps")),
                Arrays.asList("annotated helper", "open home page"));

        // outcome mapping: assertion -> failed, other throwable -> broken, no events -> skipped
        List<String> outcomes = outcomes(res);
        assertTrue(outcomes.contains("failed") && outcomes.contains("broken")
                && outcomes.contains("skipped"), "outcomes: " + outcomes);
        Map<String, Object> failing = byExternalId(res, String.valueOf(failingDef.get("external_id")));
        assertEquals(failing.get("outcome"), "failed");
        assertTrue(String.valueOf(failing.get("message")).contains("boom"));
        assertTrue(String.valueOf(failing.get("traces")).contains("assertionFails"));
        Map<String, Object> broken = byName(res, "infrastructureBreaks");
        assertEquals(broken.get("outcome"), "broken");
        assertTrue(String.valueOf(broken.get("message")).contains("io error"));

        // a matched @Test(expectedExceptions) is a SUCCESS carrying a throwable: the outcome must
        // come from the status, never from getThrowable() != null
        assertEquals(byExternalId(res, "E2ENG-EXPECTED-1").get("outcome"), "passed");

        // @Test(enabled = false) fires no TestNG event at all - read off the excluded methods
        Map<String, Object> disabled = byName(res, "skippedByFlag");
        assertNotNull(disabled, "a disabled test is still reported");
        assertEquals(disabled.get("outcome"), "skipped");
        assertEquals(disabled.get("message"), "disabled with @Test(enabled = false)");

        // an unmet dependency: skipped, naming the method that did not succeed
        Map<String, Object> dependent = byExternalId(res, "E2ENG-DEP-1");
        assertEquals(dependent.get("outcome"), "skipped");
        assertTrue(String.valueOf(dependent.get("message")).contains("assertionFails"),
                "skip reason names the failed dependency: " + dependent.get("message"));
    }

    @Test
    public void realtimeStreamsPerClassWithClassTeardown() {
        // the class is streamed at the <test> boundary, not at IClassListener.onAfterClass, which
        // fires BEFORE @AfterClass - streaming there would ship results without class teardown.
        configure(map("doqa.adapterMode", "1", "doqa.testRunId", "99",
                "doqa.importRealtime", "true"));
        run(DemoLoginScenario.class);

        assertEquals(all("POST", "/api/autotests/upsert").size(), 1);
        List<Map<String, Object>> res = results();
        assertEquals(res.size(), 6, "one results POST per finished class");
        List<Map<String, Object>> teardown =
                maps(byExternalId(res, "E2ENG-LOGIN-1").get("teardown_results"));
        assertEquals(teardown.get(0).get("title"), "tearDown");
        assertEquals(teardown.get(teardown.size() - 1).get("title"), "afterClassCleanup",
                "@AfterClass travels with the streamed class batch");
    }

    // ------------------------------------------------------------------ data providers
    @Test
    public void dataProviderInvocationsCarryRealArgumentValues() {
        configure(map("doqa.adapterMode", "2"));
        run(DataProviderScenario.class);

        List<Map<String, Object>> defs = defs();
        // {param} placeholder: one autotest per invocation; without it: one autotest for both
        assertNotNull(byExternalId(defs, "E2ENG-BROWSER-chrome"),
                "placeholder substituted into externalId (chrome)");
        assertNotNull(byExternalId(defs, "E2ENG-BROWSER-firefox"),
                "placeholder substituted into externalId (firefox)");
        assertEquals(defs.size(), 3, "2 placeholder invocations + 1 collapsed def");

        String collapsed = null;
        for (Map<String, Object> def : defs) {
            if (String.valueOf(def.get("external_id")).startsWith("testng:")) {
                collapsed = String.valueOf(def.get("external_id"));
            }
        }
        assertNotNull(collapsed, "collapsed def keeps the signature-hash id");

        List<Map<String, Object>> res = results();
        assertEquals(res.size(), 4);
        int collapsedResults = 0;
        List<String> values = new ArrayList<>();
        for (Map<String, Object> r : res) {
            if (collapsed.equals(r.get("external_id"))) {
                collapsedResults++;
            }
            for (Map<String, Object> p : maps(r.get("parameters"))) {
                assertEquals(p.get("name"), "browser", "parameter named after the argument");
                values.add(String.valueOf(p.get("value")));
            }
        }
        assertEquals(collapsedResults, 2, "both invocations report into one autotest");
        Collections.sort(values);
        assertEquals(values, Arrays.asList("chrome", "chrome", "firefox", "firefox"));
    }

    // ------------------------------------------------------------------ retries
    @Test
    public void retryAttemptsAreReportedByTheirThrowableNotAsSkips() {
        configure(map("doqa.adapterMode", "2"));
        run(RetryScenario.class);

        List<Map<String, Object>> res = results();
        assertEquals(res.size(), 2, "both attempts reported");
        List<String> outcomes = outcomes(res);
        Collections.sort(outcomes);
        assertEquals(outcomes, Arrays.asList("failed", "passed"),
                "a failed retry attempt arrives as a SKIP and must not be reported as skipped");
        for (Map<String, Object> r : res) {
            assertEquals(r.get("external_id"), "E2ENG-RETRY-1", "attempts share one autotest");
        }
    }

    // ------------------------------------------------------------------ broken fixtures
    @Test
    public void brokenFixturesReportEveryTestWithTheFixtureFailure() {
        configure(map("doqa.adapterMode", "2"));
        run(BrokenFixtureScenario.class, BrokenClassFixtureScenario.class);

        assertEquals(BrokenFixtureScenario.executed, 0, "a test killed by its setup does not run");
        assertEquals(BrokenClassFixtureScenario.executed, 0);

        List<Map<String, Object>> res = results();
        assertEquals(res.size(), 3, "1 test of the broken @BeforeMethod class + 2 of the other");

        Map<String, Object> methodFixture = byExternalId(res, "E2ENG-BF-1");
        assertEquals(methodFixture.get("outcome"), "skipped");
        String message = String.valueOf(methodFixture.get("message"));
        assertTrue(message.contains("brokenSetup") && message.contains("setup down"), message);
        Map<String, Object> setupNode = maps(methodFixture.get("setup_results")).get(0);
        assertEquals(setupNode.get("title"), "brokenSetup");
        assertEquals(setupNode.get("outcome"), "broken", "the fixture node carries the failure");
        assertTrue(String.valueOf(setupNode.get("message")).contains("setup down"));

        for (String id : Arrays.asList("E2ENG-BC-1", "E2ENG-BC-2")) {
            Map<String, Object> r = byExternalId(res, id);
            assertNotNull(r, id + " reported although the class setup died");
            assertEquals(r.get("outcome"), "skipped");
            assertTrue(String.valueOf(r.get("message")).contains("brokenClassSetup"),
                    String.valueOf(r.get("message")));
            Map<String, Object> classNode = maps(r.get("setup_results")).get(0);
            assertEquals(classNode.get("title"), "brokenClassSetup");
            assertEquals(classNode.get("outcome"), "broken");
            assertTrue(String.valueOf(classNode.get("message")).contains("class setup down"));
        }
    }

    // ------------------------------------------------------------------ mode 0
    @Test
    public void mode0DeselectsTestsMissingFromTheRun() {
        configure(map("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        run(SelectScenario.class);

        // selective list fetched once, no run created
        only("GET", "/autotests");
        assertEquals(all("POST", "/api/autotests/test-runs").size(), 0,
                "mode 0 must not create a run");

        assertEquals(SelectScenario.selectedExecuted, 1, "selected test runs");
        assertEquals(SelectScenario.deselectedExecuted, 0,
                "a deselected test must not be executed, not merely unreported");
        assertEquals(SelectScenario.dependentExecuted, 0);
        assertEquals(SelectBaseScenario.baseExecuted, 0);

        Map<String, Object> body = Json.parseObject(only("POST", "/api/autotests/results").body);
        assertEquals(String.valueOf(body.get("test_run_id")), "77");
        List<Map<String, Object>> res = maps(body.get("results"));
        assertEquals(res.size(), 1);
        assertNotNull(byExternalId(res, "E2ENG-SEL-1"));
        assertNull(byExternalId(res, "E2ENG-SEL-2"), "a deselected test is reported nowhere");
    }

    @Test
    public void mode0PullsBackDeselectedDependenciesWithoutReportingThem() {
        // dropping a method a kept one depends on aborts the whole <test> block in TestNG, so the
        // transitive closure of dependsOnMethods / dependsOnGroups is executed and gated at report
        // time instead.
        configure(map("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[{\"externalId\":\"E2ENG-SEL-DEP\"},"
                + "{\"externalId\":\"E2ENG-GRP-1\"}]}";

        run(SelectScenario.class, SelectGroupScenario.class);

        assertEquals(SelectScenario.dependentExecuted, 1, "the selected test ran");
        assertEquals(SelectBaseScenario.baseExecuted, 1,
                "its dependency, declared in the base class and NOT in the run, was pulled back in");
        assertEquals(SelectGroupScenario.dependentExecuted, 1);
        assertEquals(SelectGroupScenario.groupExecuted, 1,
                "a whole deselected dependsOnGroups group was pulled back in");
        assertEquals(SelectScenario.selectedExecuted, 0);
        assertEquals(SelectScenario.deselectedExecuted, 0);

        List<Map<String, Object>> res = results();
        assertEquals(res.size(), 2, "only what the run asked for is reported");
        assertNotNull(byExternalId(res, "E2ENG-SEL-DEP"));
        assertNotNull(byExternalId(res, "E2ENG-GRP-1"));
        assertNull(byExternalId(res, "E2ENG-SEL-BASE"),
                "a dependency executed only to keep TestNG happy stays out of the run");
        assertNull(byExternalId(res, "E2ENG-GRP-PREP"));
    }

    @Test
    public void mode0KeepsPlaceholderTemplatesAndGatesThemPerInvocation() {
        // a templated externalId cannot be compared literally before the arguments are known: the
        // whole method stays in the run when any selected id matches its wildcard form, and the
        // exact per-invocation ids decide at report time.
        configure(map("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[{\"externalId\":\"E2ENG-BROWSER-chrome\"}]}";

        run(DataProviderScenario.class);

        assertEquals(DataProviderScenario.placeholderExecuted, 2,
                "the templated method runs whole");
        assertEquals(DataProviderScenario.collapsedExecuted, 0,
                "a method whose hash id is outside the run does not execute");

        List<Map<String, Object>> res = results();
        assertEquals(res.size(), 1);
        assertNotNull(byExternalId(res, "E2ENG-BROWSER-chrome"),
                "selected placeholder invocation uploaded");
        assertNull(byExternalId(res, "E2ENG-BROWSER-firefox"),
                "non-selected placeholder invocation gated out at report time");
    }

    @Test
    public void planOrderDrivesTheExecutionOrder() {
        configure(map("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        // the plan disagrees with both the declaration order and @Test(priority)
        selectiveResponse = "{\"ordered\":true,\"autotests\":["
                + "{\"externalId\":\"E2ENG-ORD-3\",\"position\":1},"
                + "{\"externalId\":\"E2ENG-ORD-1\",\"position\":2},"
                + "{\"externalId\":\"E2ENG-ORD-2\",\"position\":3}]}";

        run(OrderScenario.class);

        assertEquals(new ArrayList<>(OrderScenario.EXECUTED),
                Arrays.asList("E2ENG-ORD-3", "E2ENG-ORD-1", "E2ENG-ORD-2"),
                "the order the interceptor returns wins over declaration order and priority");
    }

    // ------------------------------------------------------------------ parallel
    @Test
    public void parallelMethodsKeepStepsAndFixturesPerTest() {
        configure(map("doqa.adapterMode", "1", "doqa.testRunId", "55"));

        XmlSuite suite = new XmlSuite();
        suite.setName("doqa-parallel");
        suite.setParallel(XmlSuite.ParallelMode.METHODS);
        suite.setThreadCount(4);
        XmlTest test = new XmlTest(suite);
        test.setName("parallel-methods");
        test.setXmlClasses(Collections.singletonList(new XmlClass(ParallelScenario.class)));

        run(suite);

        List<Map<String, Object>> res = results();
        assertEquals(res.size(), 4, "every test reported exactly once");
        for (String id : Arrays.asList("E2ENG-PAR-1", "E2ENG-PAR-2", "E2ENG-PAR-3", "E2ENG-PAR-4")) {
            Map<String, Object> r = byExternalId(res, id);
            assertNotNull(r, id);
            assertEquals(titles(r.get("step_results")),
                    Arrays.asList("first of " + id, "second of " + id),
                    "no step leakage between concurrent tests");
            assertEquals(titles(r.get("setup_results")), Arrays.asList("setUp"));
            assertEquals(titles(r.get("teardown_results")), Arrays.asList("tearDown"));
        }
    }

    // ------------------------------------------------------------------ files sink
    @Test
    public void filesModeEmitsParserCompatibleAllureResultsWithoutNetwork() throws IOException {
        Path resultsDir = Files.createTempDirectory("doqa-e2eng-files");
        setProp("doqa.reporting", "files");
        setProp("doqa.resultsDir", resultsDir.toString());
        DoqaSession.reset();

        run(DemoLoginScenario.class);

        assertTrue(recorded.isEmpty(), "files sink must not touch the network");
        List<Map<String, Object>> results = readResults(resultsDir);
        assertEquals(results.size(), 6, "4 executed + 1 disabled + 1 skipped by dependency");

        Map<String, Object> login = byLabel(results, "E2ENG-LOGIN-1");
        assertNotNull(login, "login result attributed via doqa_id label");
        assertEquals(login.get("status"), "passed", String.valueOf(login.get("statusDetails")));
        assertEquals(login.get("historyId"), "E2ENG-LOGIN-1");
        Map<String, String> labels = labelMap(login.get("labels"));
        assertEquals(labels.get("doqa_cases"), "901");
        assertEquals(labels.get("package"), "app.doqa.e2eng");
        assertEquals(labels.get("framework"), "testng");

        // steps land in result.steps (parser: build_steps_tree(result.steps, fixtures))
        List<Map<String, Object>> steps = maps(login.get("steps"));
        assertEquals(steps.get(0).get("name"), "open login page");
        assertEquals(maps(steps.get(0).get("steps")).get(0).get("name"), "annotated helper",
                "LTW @Step child present in file emit");
        assertNotNull(steps.get(0).get("start"),
                "step start/stop let the parser recompute durationMs");
        assertNotNull(steps.get(0).get("stop"));

        // attachment copied next to the JSON and referenced as {name, source, type}
        Map<String, Object> att = maps(login.get("attachments")).get(0);
        assertEquals(att.get("type"), "image/png");
        assertTrue(String.valueOf(att.get("name")).startsWith("doqa-e2eng-shot"));
        assertTrue(Files.exists(resultsDir.resolve(String.valueOf(att.get("source")))),
                "attachment payload copied into the results dir");

        // fixtures travel via containers referencing the result uuid: the per-result container
        // carries [@BeforeClass (prepended), @BeforeMethod] / [@AfterMethod]; the shared class
        // container (children = every result of the class) carries @AfterClass.
        Map<String, Object> perResult = null;
        Map<String, Object> classContainer = null;
        for (Map<String, Object> c : containersFor(resultsDir, String.valueOf(login.get("uuid")))) {
            if (((List<?>) c.get("children")).size() == 1) {
                perResult = c;
            } else {
                classContainer = c;
            }
        }
        assertNotNull(perResult, "per-result container with fixtures");
        List<Map<String, Object>> befores = maps(perResult.get("befores"));
        assertEquals(befores.get(0).get("name"), "beforeClassInit", "@BeforeClass first");
        assertEquals(befores.get(1).get("name"), "setUp");
        assertEquals(maps(perResult.get("afters")).get(0).get("name"), "tearDown");
        assertNotNull(classContainer, "shared class container for @AfterClass");
        assertEquals(((List<?>) classContainer.get("children")).size(), 6,
                "class container spans every result of the class");
        assertEquals(maps(classContainer.get("afters")).get(0).get("name"), "afterClassCleanup");

        // outcome mapping survives the file path too
        List<String> statuses = new ArrayList<>();
        for (Map<String, Object> r : results) {
            statuses.add(String.valueOf(r.get("status")));
        }
        assertTrue(statuses.contains("failed") && statuses.contains("broken")
                && statuses.contains("skipped"), "statuses: " + statuses);
    }

    // ------------------------------------------------------------------ files helpers
    private void setProp(String key, String value) {
        System.setProperty(key, value);
        propsSet.add(key);
    }

    private List<Map<String, Object>> readResults(Path dir) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files
                    .filter(p -> p.getFileName().toString().endsWith("-result.json"))
                    .collect(Collectors.toList())) {
                out.add(Json.parseObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    private List<Map<String, Object>> containersFor(Path dir, String resultUuid) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files
                    .filter(p -> p.getFileName().toString().endsWith("-container.json"))
                    .collect(Collectors.toList())) {
                Map<String, Object> container = Json.parseObject(new String(
                        Files.readAllBytes(p), StandardCharsets.UTF_8));
                Object children = container.get("children");
                if (children instanceof List && ((List<?>) children).contains(resultUuid)) {
                    out.add(container);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> byLabel(List<Map<String, Object>> results, String externalId) {
        for (Map<String, Object> r : results) {
            if (externalId.equals(labelMap(r.get("labels")).get("doqa_id"))) {
                return r;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labelMap(Object labels) {
        Map<String, String> out = new LinkedHashMap<>();
        if (labels instanceof List) {
            for (Object l : (List<Object>) labels) {
                if (l instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) l;
                    out.putIfAbsent(String.valueOf(m.get("name")), String.valueOf(m.get("value")));
                }
            }
        }
        return out;
    }
}
