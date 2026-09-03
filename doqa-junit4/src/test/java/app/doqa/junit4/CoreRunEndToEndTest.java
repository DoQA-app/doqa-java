package app.doqa.junit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.doqa.client.Json;
import app.doqa.core.DoqaSession;
import app.doqa.e2e4.AfterFailScenario;
import app.doqa.e2e4.DemoLoginScenario;
import app.doqa.e2e4.FailingBeforeClassScenario;
import app.doqa.e2e4.FixedOrderScenario;
import app.doqa.e2e4.IgnoredClassScenario;
import app.doqa.e2e4.OrderScenario;
import app.doqa.e2e4.ParamScenario;
import app.doqa.e2e4.PlainListenerScenario;
import app.doqa.e2e4.RuntimeIdScenario;
import app.doqa.e2e4.SelectRuleScenario;
import app.doqa.e2e4.SelectScenario;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.JUnitCore;

/**
 * End-to-end tests: a nested {@link JUnitCore} run executes the demo suites with the adapter
 * attached the way a host build attaches it (a {@link DoqaRunListener} on the notifier, plus
 * {@code @RunWith(DoqaRunner.class)} / the parameterized factory / the select rule where the
 * scenario opts in), AspectJ LTW for {@code @Step} (surefire runs with
 * {@code -javaagent:aspectjweaver}), config via system-property resolution and the real
 * {@code HttpClientTransport}, all against a local {@link HttpServer} faking the Autotest API.
 * Captured upsert/results/attachments/test-runs payloads are asserted against the API contract.
 *
 * <p>Machine environment is isolated per test: {@code DoqaSession.setEnvOverride} hides real
 * {@code DOQA_*} variables and {@code doqa.config} points at a non-existent file, so local
 * {@code doqa.properties} or exported credentials never leak into (or out of) these runs.
 */
public class CoreRunEndToEndTest {

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
            "{\"autotests\":[{\"externalId\":\"E2E4-SEL-1\"},{\"externalId\":\"E2E4-RULE-1\"}]}";

    private HttpServer server;
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private final List<String> propsSet = new ArrayList<>();
    /** Per-test override of the GET /autotests selective (ordered) plan response. */
    private volatile String selectiveResponse = DEFAULT_SELECTIVE_RESPONSE;

    @Before
    public void startFakeBackend() throws IOException {
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

    @After
    public void cleanup() {
        for (String p : propsSet) {
            System.clearProperty(p);
        }
        propsSet.clear();
        DoqaSession.reset();
        DoqaSession.setClientFactory(null);
        DoqaSession.setConfigOverride(null);
        DoqaSession.setEnvOverride(null);
        AdapterState.resetAll();
        if (server != null) {
            server.stop(0);
        }
        recorded.clear();
        selectiveResponse = DEFAULT_SELECTIVE_RESPONSE;
        OrderScenario.EXECUTED.clear();
        SelectScenario.selectedExecuted = 0;
        SelectScenario.deselectedExecuted = 0;
        SelectRuleScenario.selectedExecuted = 0;
        SelectRuleScenario.deselectedExecuted = 0;
    }

    private void configure(Map<String, String> extra) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("doqa.url", "http://127.0.0.1:" + server.getAddress().getPort());
        props.put("doqa.token", "E2E-TOKEN");
        props.put("doqa.spaceId", "31");
        props.put("doqa.reporting", "api");
        props.put("doqa.resultsDir", "target/doqa-e2e-results");
        props.putAll(extra);
        for (Map.Entry<String, String> e : props.entrySet()) {
            setProp(e.getKey(), e.getValue());
        }
        DoqaSession.reset();
    }

    /**
     * Nested run: the listener is attached by hand, exactly as a host does it (JUnit 4 has no
     * service-loader registration). The adapter's process-wide state is wiped first - its
     * report-once guard and the runner-managed class set would otherwise carry over from the
     * previous nested run and swallow this one's results.
     */
    private static void run(Class<?>... testClasses) {
        AdapterState.resetAll();
        DoqaSession.reset();
        JUnitCore core = new JUnitCore();
        core.addListener(new DoqaRunListener());
        core.run(testClasses);
    }

    // ------------------------------------------------------------------ helpers
    private Recorded only(String method, String pathSuffix) {
        List<Recorded> hits = all(method, pathSuffix);
        assertEquals(method + " " + pathSuffix + " count", 1, hits.size());
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

    private static List<String> titles(Object stepResults) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> step : maps(stepResults)) {
            out.add(String.valueOf(step.get("title")));
        }
        return out;
    }

    private List<Map<String, Object>> results() {
        Recorded results = only("POST", "/api/autotests/results");
        return maps(Json.parseObject(results.body).get("results"));
    }

    // ------------------------------------------------------------------ full contract
    @Test
    public void mode2FullContractThroughNestedRun() {
        configure(map("doqa.adapterMode", "2", "doqa.testRunName", "e2e run"));
        run(DemoLoginScenario.class);

        // ---- run created (mode 2) with auth + name ----
        Recorded createRun = only("POST", "/api/autotests/test-runs");
        Map<String, Object> runBody = Json.parseObject(createRun.body);
        assertEquals("E2E-TOKEN", runBody.get("token"));
        assertEquals("31", runBody.get("space_id"));
        assertEquals("e2e run", runBody.get("name"));

        // ---- attachments uploaded as multipart before results (test shot + @BeforeClass log) ----
        List<Recorded> attachments = all("POST", "/api/autotests/attachments");
        assertEquals("test screenshot + in-memory fixture attachment", 2, attachments.size());
        Recorded attachment = null;
        for (Recorded a : attachments) {
            if (a.body.contains("doqa-e2e-shot")) {
                attachment = a;
            }
        }
        assertNotNull("screenshot upload present", attachment);
        assertTrue("file part present", attachment.body.contains("name=\"file\""));
        assertTrue("token field present", attachment.body.contains("E2E-TOKEN"));
        assertTrue("content type inferred from the file name",
                attachment.body.contains("Content-Type: image/png"));
        boolean fixtureAttachment = false;
        for (Recorded a : attachments) {
            fixtureAttachment |= a.body.contains("init.log");
        }
        assertTrue("in-memory @BeforeClass attachment uploaded", fixtureAttachment);

        // ---- batched upsert: full def model ----
        Recorded upsert = only("POST", "/api/autotests/upsert");
        Map<String, Object> upsertBody = Json.parseObject(upsert.body);
        assertEquals("E2E-TOKEN", upsertBody.get("token"));
        List<Map<String, Object>> defs = maps(upsertBody.get("autotests"));
        assertEquals("3 executed tests + 1 ignored", 4, defs.size());

        Map<String, Object> login = byExternalId(defs, "E2E4-LOGIN-1");
        assertNotNull("explicit externalId def present", login);
        assertEquals("Login works", login.get("title"));
        assertEquals("app.doqa.e2e4", login.get("namespace"));
        assertEquals("DemoLoginScenario", login.get("classname"));
        assertEquals("loginHappyPath", login.get("runner_method"));
        List<Object> labels = list(login.get("labels"));
        assertTrue("class+method labels merged",
                labels.contains("e2e-class") && labels.contains("smoke"));
        assertTrue("native JUnit @Category lands in tags",
                list(login.get("tags")).contains("NativeTag"));
        Map<String, Object> link = maps(login.get("links")).get(0);
        assertEquals("defect", link.get("type"));
        assertEquals("http://tracker/BUG-1", link.get("url"));
        assertEquals(Arrays.asList(901L), login.get("case_ids"));

        // def steps: @Before -> before, test body steps -> step (with the aspect-woven children),
        // @After -> after (@BeforeClass/@AfterClass are execution fixtures, not definition steps)
        List<Map<String, Object>> steps = maps(login.get("steps"));
        assertEquals(3, steps.size());
        assertEquals("before", steps.get(0).get("kind"));
        assertEquals("setUp", steps.get(0).get("title"));
        Map<String, Object> callStep = steps.get(1);
        assertEquals("step", callStep.get("kind"));
        assertEquals("open login page", callStep.get("title"));
        List<Map<String, Object>> nested = maps(callStep.get("steps"));
        assertEquals("AspectJ LTW must weave the @Step helper",
                "annotated helper", nested.get(0).get("title"));
        assertEquals("{param} placeholder resolved from the method argument",
                "open home page", nested.get(1).get("title"));
        assertEquals("after", steps.get(2).get("kind"));
        assertEquals("tearDown", steps.get(2).get("title"));

        // hash-fallback attribution for the un-annotated tests
        Map<String, Object> failingDef = null;
        for (Map<String, Object> def : defs) {
            if ("assertionFails".equals(def.get("name"))) {
                failingDef = def;
            }
        }
        assertNotNull(failingDef);
        assertTrue("signature-hash fallback externalId",
                String.valueOf(failingDef.get("external_id")).startsWith("junit4:"));

        // ---- batched results: outcomes, params, steps, fixtures, attachments ----
        Recorded results = only("POST", "/api/autotests/results");
        Map<String, Object> resultsBody = Json.parseObject(results.body);
        assertEquals("4242", String.valueOf(resultsBody.get("test_run_id")));
        List<Map<String, Object>> res = maps(resultsBody.get("results"));
        assertEquals(4, res.size());

        Map<String, Object> loginRes = byExternalId(res, "E2E4-LOGIN-1");
        assertEquals("passed", loginRes.get("outcome"));
        assertTrue(String.valueOf(loginRes.get("message")).contains("hello from runtime"));
        List<Map<String, Object>> params = maps(loginRes.get("parameters"));
        assertEquals("browser", params.get(0).get("name"));
        assertEquals("chrome", params.get(0).get("value"));
        assertEquals("555", String.valueOf(
                maps(loginRes.get("attachments")).get(0).get("media_file_id")));
        assertNotNull(loginRes.get("started_on"));
        assertNotNull(loginRes.get("duration_ms"));

        // class fixtures (batch flush): @BeforeClass prepended to setup, @AfterClass appended to teardown
        List<Map<String, Object>> setup = maps(loginRes.get("setup_results"));
        assertEquals("@BeforeClass first in setup", "beforeClassInit", setup.get(0).get("title"));
        assertEquals("Doqa.step inside @BeforeClass nests under the fixture node",
                "prepare db", maps(setup.get(0).get("steps")).get(0).get("title"));
        assertEquals("Doqa.addAttachment inside @BeforeClass survives",
                1, maps(setup.get(0).get("attachments")).size());
        assertEquals("setUp", setup.get(1).get("title"));
        List<Map<String, Object>> teardown = maps(loginRes.get("teardown_results"));
        assertEquals("tearDown", teardown.get(0).get("title"));
        assertEquals("@AfterClass last in teardown",
                "afterClassCleanup", teardown.get(teardown.size() - 1).get("title"));
        Map<String, Object> stepRes = maps(loginRes.get("step_results")).get(0);
        assertEquals("open login page", stepRes.get("title"));
        assertEquals("passed", stepRes.get("outcome"));
        assertEquals("annotated helper", maps(stepRes.get("steps")).get(0).get("title"));

        // outcome mapping: assertion -> failed, unexpected exception -> broken, @Ignore -> skipped
        List<String> outcomes = new ArrayList<>();
        for (Map<String, Object> r : res) {
            outcomes.add(String.valueOf(r.get("outcome")));
        }
        assertTrue("outcomes: " + outcomes, outcomes.contains("failed")
                && outcomes.contains("broken") && outcomes.contains("skipped"));
        for (Map<String, Object> r : res) {
            if ("failed".equals(r.get("outcome"))) {
                assertTrue(String.valueOf(r.get("message")).contains("boom"));
                assertTrue(String.valueOf(r.get("traces")).contains("assertionFails"));
            }
            if ("broken".equals(r.get("outcome"))) {
                assertTrue(String.valueOf(r.get("message")).contains("io error"));
            }
        }
    }

    // ------------------------------------------------------------------ baseline layer
    @Test
    public void plainListenerReportsWithoutRunnerButWithoutPerFixtureNodes() {
        configure(map("doqa.adapterMode", "2"));
        run(PlainListenerScenario.class);

        List<Map<String, Object>> res = results();
        assertEquals(1, res.size());
        Map<String, Object> plain = byExternalId(res, "E2E4-PLAIN-1");
        assertNotNull(plain);
        assertEquals("passed", plain.get("outcome"));

        // Doqa.step lands in the call bucket - the default phase without a runner
        assertEquals(Arrays.asList("plain step"), titles(plain.get("step_results")));

        // @Before/@After produce no nodes of their own; the class fixtures still arrive, but the
        // listener can only bracket the whole @BeforeClass block as one aggregate node
        List<Map<String, Object>> setup = maps(plain.get("setup_results"));
        assertEquals("two @BeforeClass methods collapse into one node", 1, setup.size());
        assertEquals("@BeforeClass", setup.get(0).get("title"));
        assertEquals("a step taken inside a class fixture nests under it",
                "connect", maps(setup.get(0).get("steps")).get(0).get("title"));
        assertEquals(Arrays.asList("closeConnection"), titles(plain.get("teardown_results")));
    }

    // ------------------------------------------------------------------ class-level events
    @Test
    public void failedBeforeClassReportsEveryTestOfTheClass() {
        configure(map("doqa.adapterMode", "2"));
        run(FailingBeforeClassScenario.class);

        List<Map<String, Object>> res = results();
        assertEquals(2, res.size());
        for (String id : Arrays.asList("E2E4-BC-1", "E2E4-BC-2")) {
            Map<String, Object> r = byExternalId(res, id);
            assertNotNull(id + " synthesized from the failed class", r);
            assertEquals("broken", r.get("outcome"));
            assertTrue(String.valueOf(r.get("message")).contains("infra down"));
            Map<String, Object> fixture = maps(r.get("setup_results")).get(0);
            assertEquals("boom", fixture.get("title"));
            assertEquals("the class fixture node carries the failure",
                    "broken", fixture.get("outcome"));
        }
    }

    @Test
    public void ignoredClassReportsEveryTestAsSkipped() {
        configure(map("doqa.adapterMode", "2"));
        run(IgnoredClassScenario.class);

        List<Map<String, Object>> res = results();
        assertEquals(2, res.size());
        for (String id : Arrays.asList("E2E4-IGN-1", "E2E4-IGN-2")) {
            Map<String, Object> r = byExternalId(res, id);
            assertNotNull(id + " expanded from the single class-level skip event", r);
            assertEquals("skipped", r.get("outcome"));
            assertEquals("maintenance window", r.get("message"));
        }
    }

    @Test
    public void bodyAndTeardownFailuresMergeIntoOneResult() {
        configure(map("doqa.adapterMode", "2"));
        run(AfterFailScenario.class);

        List<Map<String, Object>> res = results();
        assertEquals("two testFailure callbacks, one result", 1, res.size());
        Map<String, Object> r = byExternalId(res, "E2E4-AF-1");
        assertNotNull(r);
        assertEquals("a non-assertion throwable wins the outcome", "broken", r.get("outcome"));
        String message = String.valueOf(r.get("message"));
        assertTrue(message, message.contains("body failed") && message.contains("teardown failed"));
    }

    // ------------------------------------------------------------------ parameterized
    @Test
    public void parameterizedInvocationsCarryRealArgumentValues() {
        configure(map("doqa.adapterMode", "2"));
        run(ParamScenario.class);

        List<Map<String, Object>> defs =
                maps(Json.parseObject(only("POST", "/api/autotests/upsert").body).get("autotests"));
        // {param} placeholder: one autotest per invocation; without it: one autotest for both
        assertNotNull("placeholder substituted into externalId (alpha)",
                byExternalId(defs, "E2E4-PARAM-alpha"));
        assertNotNull("placeholder substituted into externalId (beta)",
                byExternalId(defs, "E2E4-PARAM-beta"));
        assertEquals("2 placeholder invocations + 1 collapsed def", 3, defs.size());

        List<Map<String, Object>> res = results();
        assertEquals(4, res.size());
        String collapsed = null;
        for (Map<String, Object> def : defs) {
            if (String.valueOf(def.get("external_id")).startsWith("junit4:")) {
                collapsed = String.valueOf(def.get("external_id"));
            }
        }
        assertNotNull("collapsed def keeps the signature-hash id", collapsed);
        int collapsedResults = 0;
        for (Map<String, Object> r : res) {
            if (collapsed.equals(r.get("external_id"))) {
                collapsedResults++;
            }
        }
        assertEquals("both invocations report into one autotest", 2, collapsedResults);

        // the runner factory is what makes the ACTUAL values available (JUnit 4 publishes none)
        List<String> values = new ArrayList<>();
        for (Map<String, Object> r : res) {
            for (Map<String, Object> p : maps(r.get("parameters"))) {
                assertEquals("parameter named after the @Parameter field", "value", p.get("name"));
                values.add(String.valueOf(p.get("value")));
            }
        }
        Collections.sort(values);
        assertEquals(Arrays.asList("alpha", "alpha", "beta", "beta"), values);
    }

    // ------------------------------------------------------------------ mode 0
    @Test
    public void mode0DeselectsPhysicallyWithTheRunnerAndTheRule() {
        configure(map("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        run(SelectScenario.class, SelectRuleScenario.class);

        // selective list fetched once, no run created
        only("GET", "/autotests");
        assertEquals("mode 0 must not create a run",
                0, all("POST", "/api/autotests/test-runs").size());

        assertEquals("selected test runs", 1, SelectScenario.selectedExecuted);
        assertEquals("the runner must not execute a deselected test",
                0, SelectScenario.deselectedExecuted);
        assertEquals("selected test runs", 1, SelectRuleScenario.selectedExecuted);
        assertEquals("the rule must abort the body of a deselected test",
                0, SelectRuleScenario.deselectedExecuted);

        Recorded results = only("POST", "/api/autotests/results");
        Map<String, Object> body = Json.parseObject(results.body);
        assertEquals("77", String.valueOf(body.get("test_run_id")));
        List<Map<String, Object>> res = maps(body.get("results"));
        assertEquals(2, res.size());
        assertNotNull(byExternalId(res, "E2E4-SEL-1"));
        assertNotNull(byExternalId(res, "E2E4-RULE-1"));
        assertNull("a deselected test is reported nowhere", byExternalId(res, "E2E4-SEL-2"));
        assertNull("a rule-skipped test is reported nowhere", byExternalId(res, "E2E4-RULE-2"));
    }

    @Test
    public void mode0StopsATestWhoseRuntimeIdIsOutsideTheRun() {
        // a plain suite has no deselection hook: an id pinned inside the body is judged there
        configure(map("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[{\"externalId\":\"E2E4-RUN-1\"}]}";
        RuntimeIdScenario.selectedExecuted = 0;
        RuntimeIdScenario.deselectedExecuted = 0;

        run(RuntimeIdScenario.class);

        assertEquals("selected test runs", 1, RuntimeIdScenario.selectedExecuted);
        assertEquals("a test that pins an id outside the run stops at Doqa.addExternalId",
                0, RuntimeIdScenario.deselectedExecuted);

        List<Map<String, Object>> res = maps(
                Json.parseObject(only("POST", "/api/autotests/results").body).get("results"));
        assertEquals(1, res.size());
        assertNotNull(byExternalId(res, "E2E4-RUN-1"));
        assertNull("a skipped test is reported nowhere", byExternalId(res, "E2E4-RUN-2"));
    }

    @Test
    public void planOrderIsHonouredUnlessTheClassPinsItsOwn() {
        configure(map("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        // plan asks for gamma/alpha/beta, and for FIX-B before FIX-A - which @FixMethodOrder forbids
        selectiveResponse = "{\"ordered\":true,\"autotests\":["
                + "{\"externalId\":\"E2E4-ORD-3\",\"position\":1},"
                + "{\"externalId\":\"E2E4-ORD-1\",\"position\":2},"
                + "{\"externalId\":\"E2E4-ORD-2\",\"position\":3},"
                + "{\"externalId\":\"E2E4-FIX-B\",\"position\":4},"
                + "{\"externalId\":\"E2E4-FIX-A\",\"position\":5}]}";

        run(OrderScenario.class, FixedOrderScenario.class);

        assertEquals("plan order inside the class; @FixMethodOrder keeps its own",
                Arrays.asList("E2E4-ORD-3", "E2E4-ORD-1", "E2E4-ORD-2", "E2E4-FIX-A", "E2E4-FIX-B"),
                new ArrayList<>(OrderScenario.EXECUTED));
    }

    // ------------------------------------------------------------------ files sink
    @Test
    public void filesModeEmitsParserCompatibleAllureResultsWithoutNetwork() throws IOException {
        Path resultsDir = Files.createTempDirectory("doqa-e2e4-files");
        setProp("doqa.reporting", "files");
        setProp("doqa.resultsDir", resultsDir.toString());

        run(DemoLoginScenario.class);

        assertTrue("files sink must not touch the network", recorded.isEmpty());
        List<Map<String, Object>> results = readResults(resultsDir);
        assertEquals("3 executed tests + 1 ignored", 4, results.size());

        Map<String, Object> login = byLabel(results, "E2E4-LOGIN-1");
        assertNotNull("login result attributed via doqa_id label", login);
        assertEquals(String.valueOf(login.get("statusDetails")), "passed", login.get("status"));
        assertEquals("E2E4-LOGIN-1", login.get("historyId"));
        Map<String, String> labels = labelMap(login.get("labels"));
        assertEquals("901", labels.get("doqa_cases"));
        assertEquals("app.doqa.e2e4", labels.get("package"));
        assertEquals("junit4", labels.get("framework"));

        // steps land in result.steps (parser: build_steps_tree(result.steps, fixtures))
        List<Map<String, Object>> steps = maps(login.get("steps"));
        assertEquals("open login page", steps.get(0).get("name"));
        assertEquals("LTW @Step child present in file emit",
                "annotated helper", maps(steps.get(0).get("steps")).get(0).get("name"));
        assertNotNull("step start/stop let the parser recompute durationMs", steps.get(0).get("start"));
        assertNotNull(steps.get(0).get("stop"));

        // attachment copied next to the JSON and referenced as {name, source, type}
        Map<String, Object> att = maps(login.get("attachments")).get(0);
        assertEquals("image/png", att.get("type"));
        assertTrue(String.valueOf(att.get("name")).startsWith("doqa-e2e-shot"));
        assertTrue("attachment payload copied into the results dir",
                Files.exists(resultsDir.resolve(String.valueOf(att.get("source")))));

        // fixtures travel via containers referencing the result uuid: the per-result container
        // carries [@BeforeClass (prepended), setUp] / [tearDown]; the shared class container
        // (children = all results) carries @AfterClass (known only at plan end).
        Map<String, Object> perResult = null;
        Map<String, Object> classContainer = null;
        for (Map<String, Object> c : containersFor(resultsDir, String.valueOf(login.get("uuid")))) {
            if (((List<?>) c.get("children")).size() == 1) {
                perResult = c;
            } else {
                classContainer = c;
            }
        }
        assertNotNull("per-result container with fixtures", perResult);
        List<Map<String, Object>> befores = maps(perResult.get("befores"));
        assertEquals("@BeforeClass first", "beforeClassInit", befores.get(0).get("name"));
        assertEquals("setUp", befores.get(1).get("name"));
        assertEquals("tearDown", maps(perResult.get("afters")).get(0).get("name"));
        assertNotNull("shared class container for @AfterClass", classContainer);
        assertEquals("class container spans every result of the class",
                4, ((List<?>) classContainer.get("children")).size());
        assertEquals("afterClassCleanup", maps(classContainer.get("afters")).get(0).get("name"));

        // outcome mapping survives the file path too
        List<String> statuses = new ArrayList<>();
        for (Map<String, Object> r : results) {
            statuses.add(String.valueOf(r.get("status")));
        }
        assertTrue("statuses: " + statuses, statuses.contains("failed")
                && statuses.contains("broken") && statuses.contains("skipped"));
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
}
