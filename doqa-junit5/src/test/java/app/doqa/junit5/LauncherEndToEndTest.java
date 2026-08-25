package app.doqa.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import app.doqa.client.Json;
import app.doqa.core.DoqaSession;
import app.doqa.e2e.DemoLoginScenario;
import app.doqa.e2e.DisabledClassScenario;
import app.doqa.e2e.FactoryScenario;
import app.doqa.e2e.FailingBeforeAllScenario;
import app.doqa.e2e.OrderLog;
import app.doqa.e2e.OrderScenarioA;
import app.doqa.e2e.OrderScenarioB;
import app.doqa.e2e.ParallelScenarioA;
import app.doqa.e2e.ParallelScenarioB;
import app.doqa.e2e.SelectDemoScenario;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * End-to-end tests: a nested JUnit Platform launcher runs the demo suites with the adapter
 * registered the same way a host build gets it: ServiceLoader listener + post-discovery filter,
 * Jupiter extension autodetection, AspectJ LTW for {@code @Step} (surefire runs with
 * {@code -javaagent:aspectjweaver}), config via system-property resolution and the real
 * {@code HttpClientTransport}, all against a local {@link HttpServer} faking the Autotest API.
 * Captured upsert/results/attachments/test-runs payloads are asserted against the API contract.
 *
 * <p>Machine environment is isolated per test: {@code DoqaSession.setEnvOverride} hides real
 * {@code DOQA_*} variables and {@code doqa.config} points at a non-existent file, so local
 * {@code doqa.properties} or exported credentials never leak into (or out of) these runs.
 */
class LauncherEndToEndTest {

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
            "{\"autotests\":[{\"externalId\":\"E2E-SEL-1\"},{\"externalId\":\"E2E-P-42\"}]}";

    private HttpServer server;
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private final List<String> propsSet = new ArrayList<>();
    /** Per-test override of the GET /autotests selective (ordered) plan response. */
    private volatile String selectiveResponse = DEFAULT_SELECTIVE_RESPONSE;

    @BeforeEach
    void startFakeBackend() throws IOException {
        DoqaSession.setEnvOverride(Map.of());
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

    @AfterEach
    void cleanup() {
        for (String p : propsSet) {
            System.clearProperty(p);
        }
        propsSet.clear();
        DoqaSession.reset();
        DoqaSession.setClientFactory(null);
        DoqaSession.setConfigOverride(null);
        DoqaSession.setEnvOverride(null);
        if (server != null) {
            server.stop(0);
        }
        recorded.clear();
        selectiveResponse = DEFAULT_SELECTIVE_RESPONSE;
        OrderLog.EXECUTED.clear();
    }

    private void configure(Map<String, String> extra) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("doqa.url", "http://127.0.0.1:" + server.getAddress().getPort());
        props.put("doqa.token", "E2E-TOKEN");
        props.put("doqa.spaceId", "31");
        props.put("doqa.reporting", "api");
        props.putAll(extra);
        for (Map.Entry<String, String> e : props.entrySet()) {
            System.setProperty(e.getKey(), e.getValue());
            propsSet.add(e.getKey());
        }
        DoqaSession.reset();
    }

    /** Nested launch: listeners/filters come from ServiceLoader, exactly like a host build. */
    private static void launch(Class<?>... testClasses) {
        launch(Map.of(), testClasses);
    }

    /** Nested launch with extra configuration parameters (opt-in plan orderers). */
    private static void launch(Map<String, String> extraConfig, Class<?>... testClasses) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true");
        for (Class<?> testClass : testClasses) {
            builder.selectors(DiscoverySelectors.selectClass(testClass));
        }
        for (Map.Entry<String, String> e : extraConfig.entrySet()) {
            builder.configurationParameter(e.getKey(), e.getValue());
        }
        LauncherDiscoveryRequest request = builder.build();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request);
    }

    // ------------------------------------------------------------------ helpers
    private Recorded only(String method, String pathSuffix) {
        List<Recorded> hits = all(method, pathSuffix);
        assertEquals(1, hits.size(), method + " " + pathSuffix + " count");
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

    // ------------------------------------------------------------------ scenarios
    @Test
    void mode2FullContractThroughRealLauncher() {
        configure(Map.of("doqa.adapterMode", "2", "doqa.testRunName", "e2e run"));
        launch(DemoLoginScenario.class);

        // ---- run created (mode 2) with auth + name ----
        Recorded createRun = only("POST", "/api/autotests/test-runs");
        Map<String, Object> runBody = Json.parseObject(createRun.body);
        assertEquals("E2E-TOKEN", runBody.get("token"));
        assertEquals("31", runBody.get("space_id"));
        assertEquals("e2e run", runBody.get("name"));

        // ---- attachments uploaded as multipart before results (test shot + @BeforeAll log) ----
        List<Recorded> attachments = all("POST", "/api/autotests/attachments");
        assertEquals(2, attachments.size(), "test screenshot + in-memory fixture attachment");
        Recorded attachment = attachments.stream()
                .filter(a -> a.body.contains("doqa-e2e-shot")).findFirst().orElse(null);
        assertNotNull(attachment, "screenshot upload present");
        assertTrue(attachment.body.contains("name=\"file\""), "file part present");
        assertTrue(attachment.body.contains("E2E-TOKEN"), "token field present");
        assertTrue(attachment.body.contains("Content-Type: image/png"),
                "content type inferred from the file name");
        assertTrue(attachments.stream().anyMatch(a -> a.body.contains("init.log")),
                "in-memory @BeforeAll attachment uploaded");

        // ---- batched upsert: full def model ----
        Recorded upsert = only("POST", "/api/autotests/upsert");
        Map<String, Object> upsertBody = Json.parseObject(upsert.body);
        assertEquals("E2E-TOKEN", upsertBody.get("token"));
        List<Map<String, Object>> defs = maps(upsertBody.get("autotests"));
        assertEquals(7, defs.size(),
                "4 tests + 1 skipped + 1 deduped collapsed def + 2 placeholder invocations");

        // {param} placeholder: each invocation is a separate autotest with substituted id/title
        Map<String, Object> chromeDef = byExternalId(defs, "E2E-BROWSER-chrome");
        Map<String, Object> firefoxDef = byExternalId(defs, "E2E-BROWSER-firefox");
        assertNotNull(chromeDef, "placeholder substituted into externalId (chrome)");
        assertNotNull(firefoxDef, "placeholder substituted into externalId (firefox)");
        assertEquals("Login in chrome", chromeDef.get("title"), "placeholder in @DoqaTitle");

        Map<String, Object> login = byExternalId(defs, "E2E-LOGIN-1");
        assertNotNull(login, "explicit externalId def present");
        assertEquals("Login works", login.get("title"));
        assertEquals("app.doqa.e2e", login.get("namespace"));
        assertEquals("DemoLoginScenario", login.get("classname"));
        List<Object> labels = (List<Object>) login.get("labels");
        assertTrue(labels.contains("e2e-class") && labels.contains("smoke"), "class+method labels merged");
        assertTrue(((List<Object>) login.get("tags")).contains("native-tag"),
                "native JUnit @Tag lands in tags");
        Map<String, Object> link = maps(login.get("links")).get(0);
        assertEquals("defect", link.get("type"));
        assertEquals("http://tracker/BUG-1", link.get("url"));
        assertEquals(List.of(901L), login.get("case_ids"));

        // def steps: setUp -> before, test body steps -> step (with the aspect-woven child), tearDown -> after
        // (@BeforeAll/@AfterAll are execution fixtures, not part of the definition steps)
        List<Map<String, Object>> steps = maps(login.get("steps"));
        assertEquals("before", steps.get(0).get("kind"));
        assertEquals("setUp", steps.get(0).get("title"));
        Map<String, Object> callStep = steps.get(1);
        assertEquals("step", callStep.get("kind"));
        assertEquals("open login page", callStep.get("title"));
        List<Map<String, Object>> nested = maps(callStep.get("steps"));
        assertEquals("annotated helper", nested.get(0).get("title"),
                "AspectJ LTW must weave the @Step helper");
        assertEquals("open home page", nested.get(1).get("title"),
                "{param} placeholder resolved from the method argument");
        assertEquals("after", steps.get(steps.size() - 1).get("kind"));
        assertEquals("tearDown", steps.get(steps.size() - 1).get("title"));

        // hash-fallback attribution for the un-annotated tests
        Map<String, Object> failingDef = null;
        for (Map<String, Object> def : defs) {
            if ("assertionFails".equals(def.get("name"))
                    || String.valueOf(def.get("name")).contains("assertionFails")) {
                failingDef = def;
            }
        }
        assertNotNull(failingDef);
        assertTrue(String.valueOf(failingDef.get("external_id")).startsWith("junit5:"),
                "signature-hash fallback externalId");

        // ---- batched results: outcomes, params, steps, fixtures, attachments ----
        Recorded results = only("POST", "/api/autotests/results");
        Map<String, Object> resultsBody = Json.parseObject(results.body);
        assertEquals("4242", String.valueOf(resultsBody.get("test_run_id")));
        List<Map<String, Object>> res = maps(resultsBody.get("results"));
        assertEquals(8, res.size());

        Map<String, Object> loginRes = byExternalId(res, "E2E-LOGIN-1");
        assertEquals("passed", loginRes.get("outcome"));
        assertTrue(String.valueOf(loginRes.get("message")).contains("hello from runtime"));
        List<Map<String, Object>> params = maps(loginRes.get("parameters"));
        assertEquals("browser", params.get(0).get("name"));
        assertEquals("chrome", params.get(0).get("value"));
        assertEquals("555", String.valueOf(
                maps(loginRes.get("attachments")).get(0).get("media_file_id")));

        // class fixtures (batch flush): @BeforeAll prepended to setup, @AfterAll appended to teardown
        List<Map<String, Object>> setup = maps(loginRes.get("setup_results"));
        assertEquals("beforeAllInit", setup.get(0).get("title"), "@BeforeAll first in setup");
        assertEquals("prepare db", maps(setup.get(0).get("steps")).get(0).get("title"),
                "Doqa.step inside @BeforeAll nests under the fixture node");
        assertEquals(1, maps(setup.get(0).get("attachments")).size(),
                "Doqa.addAttachment inside @BeforeAll survives");
        assertEquals("setUp", setup.get(1).get("title"));
        List<Map<String, Object>> teardown = maps(loginRes.get("teardown_results"));
        assertEquals("tearDown", teardown.get(0).get("title"));
        assertEquals("afterAllCleanup", teardown.get(teardown.size() - 1).get("title"),
                "@AfterAll last in teardown");
        Map<String, Object> stepRes = maps(loginRes.get("step_results")).get(0);
        assertEquals("open login page", stepRes.get("title"));
        assertEquals("passed", stepRes.get("outcome"));
        assertEquals("annotated helper", maps(stepRes.get("steps")).get(0).get("title"));
        assertNotNull(loginRes.get("started_on"));
        assertNotNull(loginRes.get("duration_ms"));

        // outcome mapping: assertion -> failed, unexpected exception -> broken, @Disabled -> skipped
        List<String> outcomes = new ArrayList<>();
        for (Map<String, Object> r : res) {
            outcomes.add(String.valueOf(r.get("outcome")));
        }
        assertTrue(outcomes.contains("failed") && outcomes.contains("broken")
                && outcomes.contains("skipped"), "outcomes: " + outcomes);
        for (Map<String, Object> r : res) {
            if ("failed".equals(r.get("outcome"))) {
                assertTrue(String.valueOf(r.get("message")).contains("boom"));
                assertTrue(String.valueOf(r.get("traces")).contains("AssertionFailedError")
                        || String.valueOf(r.get("traces")).contains("assertionFails"));
            }
        }

        // named invocation parameters (extension + -parameters): every @ParameterizedTest
        // result carries {name:"browser", value:"chrome|firefox"}; 4 results total
        int namedBrowserParams = 0;
        for (Map<String, Object> r : res) {
            for (Map<String, Object> p : maps(r.get("parameters"))) {
                if ("browser".equals(p.get("name"))
                        && !"E2E-LOGIN-1".equals(r.get("external_id"))) {
                    namedBrowserParams++;
                }
            }
        }
        assertEquals(4, namedBrowserParams,
                "named per-argument parameters on all parameterized invocations");
    }

    @Test
    void mode0DeselectsTestsMissingFromRun() {
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        SelectDemoScenario.executed = 0;
        launch(SelectDemoScenario.class);

        // selective list fetched, no run created
        only("GET", "/autotests");
        assertEquals(0, all("POST", "/api/autotests/test-runs").size(), "mode 0 must not create a run");

        // the template (E2E-P-{v}) survives discovery because E2E-P-42 is in the run; the
        // invocation resolving to E2E-P-43 is aborted once its arguments are known => 1 + 1
        assertEquals(2, SelectDemoScenario.executed,
                "neither the deselected test nor the deselected invocation may execute");

        Recorded results = only("POST", "/api/autotests/results");
        Map<String, Object> body = Json.parseObject(results.body);
        assertEquals("77", String.valueOf(body.get("test_run_id")));
        List<Map<String, Object>> res = maps(body.get("results"));
        assertNotNull(byExternalId(res, "E2E-SEL-1"));
        assertNull(byExternalId(res, "E2E-SEL-2"));
        // per-invocation precision at report time: substituted id checked against the run list
        assertNotNull(byExternalId(res, "E2E-P-42"), "selected placeholder invocation uploaded");
        assertNull(byExternalId(res, "E2E-P-43"), "non-selected placeholder invocation dropped");
    }

    // ------------------------------------------------------------------ plan orderers
    private static final Map<String, String> ORDERER_PROPS = Map.of(
            "junit.jupiter.testclass.order.default", "app.doqa.junit5.DoqaPlanClassOrderer",
            "junit.jupiter.testmethod.order.default", "app.doqa.junit5.DoqaPlanMethodOrderer");

    @Test
    void planOrderersExecuteInPlanOrder() {
        // plan [B2, A1, B1, A2] -> classes by min-position (B=0 before A=1), methods by
        // in-class plan position (B2 before B1; A1 before A2). Inter-class interleaving is
        // impossible (class blocks): A1 (plan pos 1) still runs after the whole B block.
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"ordered\":true,\"autotests\":["
                + "{\"externalId\":\"E2E-ORD-B2\",\"position\":1},"
                + "{\"externalId\":\"E2E-ORD-A1\",\"position\":2},"
                + "{\"externalId\":\"E2E-ORD-B1\",\"position\":3},"
                + "{\"externalId\":\"E2E-ORD-A2\",\"position\":4}]}";
        OrderLog.EXECUTED.clear();

        launch(ORDERER_PROPS, OrderScenarioA.class, OrderScenarioB.class);

        assertEquals(
                List.of("E2E-ORD-B2", "E2E-ORD-B1", "E2E-ORD-A1", "E2E-ORD-A2"),
                OrderLog.EXECUTED);
    }

    @Test
    void planOrderersAreNoOpWithoutPlan() {
        // orderers configured but no ordered plan (mode 1) -> strict no-op: selector
        // (default) class order preserved, nothing dropped.
        configure(Map.of("doqa.adapterMode", "1", "doqa.testRunId", "88"));
        OrderLog.EXECUTED.clear();

        launch(ORDERER_PROPS, OrderScenarioA.class, OrderScenarioB.class);

        List<String> executed = new ArrayList<>(OrderLog.EXECUTED);
        assertEquals(4, executed.size(), "no-op must not drop tests");
        assertTrue(executed.get(0).startsWith("E2E-ORD-A") && executed.get(1).startsWith("E2E-ORD-A"),
                "default (selector) class order preserved: " + executed);
        assertTrue(executed.get(2).startsWith("E2E-ORD-B") && executed.get(3).startsWith("E2E-ORD-B"),
                "default (selector) class order preserved: " + executed);
    }

    @Test
    void planOrderersComposeWithSelectFilter() {
        // filter + orderers together: mode-0 deselect semantics intact (composition
        // first), the remaining tests run in plan order (sequence second).
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"ordered\":true,\"autotests\":["
                + "{\"externalId\":\"E2E-ORD-B1\",\"position\":1},"
                + "{\"externalId\":\"E2E-ORD-A2\",\"position\":2}]}";
        OrderLog.EXECUTED.clear();

        launch(ORDERER_PROPS, OrderScenarioA.class, OrderScenarioB.class);

        assertEquals(List.of("E2E-ORD-B1", "E2E-ORD-A2"), OrderLog.EXECUTED,
                "deselected tests must not run; selected ones follow the plan order");

        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        assertNotNull(byExternalId(res, "E2E-ORD-B1"));
        assertNotNull(byExternalId(res, "E2E-ORD-A2"));
        assertNull(byExternalId(res, "E2E-ORD-A1"), "deselected test must not be reported");
        assertNull(byExternalId(res, "E2E-ORD-B2"), "deselected test must not be reported");
    }

    @Test
    void mode1ReportsAllIntoExistingRun() {
        configure(Map.of("doqa.adapterMode", "1", "doqa.testRunId", "88"));
        SelectDemoScenario.executed = 0;
        launch(SelectDemoScenario.class);

        assertEquals(0, all("POST", "/api/autotests/test-runs").size(), "mode 1 must not create a run");
        assertEquals(4, SelectDemoScenario.executed, "mode 1 runs everything");

        Recorded results = only("POST", "/api/autotests/results");
        Map<String, Object> body = Json.parseObject(results.body);
        assertEquals("88", String.valueOf(body.get("test_run_id")));
        List<Map<String, Object>> res = maps(body.get("results"));
        assertNotNull(byExternalId(res, "E2E-SEL-1"));
        assertNotNull(byExternalId(res, "E2E-SEL-2"));
        assertNotNull(byExternalId(res, "E2E-P-42"));
        assertNotNull(byExternalId(res, "E2E-P-43"));
    }

    @Test
    void realtimeModeStreamsPerClassWithTeardown() {
        configure(Map.of("doqa.adapterMode", "1", "doqa.testRunId", "99",
                "doqa.importRealtime", "true"));
        launch(DemoLoginScenario.class);

        // one upsert + one results POST per finished class (streamed after its @AfterAll)
        assertEquals(1, all("POST", "/api/autotests/upsert").size());
        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        assertEquals(8, res.size());
        // class teardown is no longer lost in realtime: @AfterAll travels with the class batch
        Map<String, Object> loginRes = byExternalId(res, "E2E-LOGIN-1");
        List<Map<String, Object>> teardown = maps(loginRes.get("teardown_results"));
        assertEquals("afterAllCleanup", teardown.get(teardown.size() - 1).get("title"));
    }

    @Test
    void mode0KeepsTestFactoryAndAbortsDynamicTestsOutsideTheRun() {
        // a plan without identities places nothing: the container is kept and its cases are
        // judged as they pin their ids
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[{\"externalId\":\"E2E-DYN-1\"}]}";
        FactoryScenario.executed = 0;
        FactoryScenario.factoryCalls = 0;
        launch(FactoryScenario.class);

        assertEquals(1, FactoryScenario.factoryCalls, "unplaceable plan keeps the container");
        assertEquals(1, FactoryScenario.executed,
                "the factory container survives discovery, the deselected dynamic test does not run");
        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        assertNotNull(byExternalId(res, "E2E-DYN-1"), "selected dynamic test uploaded");
        assertNull(byExternalId(res, "E2E-DYN-2"), "non-selected dynamic test gated out");

        List<Map<String, Object>> defs = maps(Json.parseObject(
                only("POST", "/api/autotests/upsert").body).get("autotests"));
        Map<String, Object> def = byExternalId(defs, "E2E-DYN-1");
        assertEquals("app.doqa.e2e", def.get("namespace"), "identity a plan entry is matched by");
        assertEquals("FactoryScenario", def.get("classname"));
        assertEquals("dynamicChecks", def.get("runner_method"));
    }

    @Test
    void mode0DoesNotCryEmptyWhenOnlyGeneratedTestsSurvive() {
        // a surviving template holds the selected invocation - that is a match, not a drift
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[{\"externalId\":\"E2E-P-42\"}]}";
        List<LogRecord> logged = new ArrayList<>();
        Logger listenerLog = Logger.getLogger(DoqaTestExecutionListener.class.getName());
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        listenerLog.addHandler(capture);
        try {
            SelectDemoScenario.executed = 0;
            launch(SelectDemoScenario.class);
        } finally {
            listenerLog.removeHandler(capture);
        }

        assertEquals(1, SelectDemoScenario.executed, "only the selected invocation runs");
        for (LogRecord record : logged) {
            assertFalse(String.valueOf(record.getMessage()).contains("none of them matched"),
                    "the selection did match: " + record.getMessage());
        }
    }

    @Test
    void mode0SkipsAFactoryTheRunSelectedNothingFrom() {
        // the plan places its only autotest in another class
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[{\"externalId\":\"E2E-SEL-1\","
                + "\"namespace\":\"app.doqa.e2e\",\"classname\":\"SelectDemoScenario\","
                + "\"runnerMethod\":\"selectedTest\"}]}";
        FactoryScenario.executed = 0;
        FactoryScenario.factoryCalls = 0;
        launch(FactoryScenario.class);

        assertEquals(0, FactoryScenario.factoryCalls, "the factory method itself never runs");
        assertEquals(0, FactoryScenario.executed);
        assertTrue(all("POST", "/api/autotests/results").isEmpty(), "nothing to report");
    }

    @Test
    void mode0WithAnEmptyPlanRunsNothingAtAll() {
        // an empty selective window executes nothing, containers included
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[]}";
        FactoryScenario.executed = 0;
        FactoryScenario.factoryCalls = 0;
        launch(FactoryScenario.class);

        assertEquals(0, FactoryScenario.factoryCalls);
        assertTrue(all("POST", "/api/autotests/results").isEmpty());
    }

    @Test
    void mode0RunsAFactoryThePlanPlacesASelectedAutotestIn() {
        // the plan places a selected autotest in this factory
        configure(Map.of("doqa.adapterMode", "0", "doqa.testRunId", "77"));
        selectiveResponse = "{\"autotests\":[{\"externalId\":\"E2E-DYN-1\","
                + "\"namespace\":\"app.doqa.e2e\",\"classname\":\"FactoryScenario\","
                + "\"runnerMethod\":\"dynamicChecks\"}]}";
        FactoryScenario.executed = 0;
        FactoryScenario.factoryCalls = 0;
        launch(FactoryScenario.class);

        assertEquals(1, FactoryScenario.factoryCalls);
        assertEquals(1, FactoryScenario.executed);
        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        assertNotNull(byExternalId(res, "E2E-DYN-1"));
        assertNull(byExternalId(res, "E2E-DYN-2"));
    }

    @Test
    void runtimeIdsNeverAbortOutsideSelectiveRuns() {
        // without a selection (mode 1) Doqa.addExternalId is a plain setter
        configure(Map.of("doqa.adapterMode", "1", "doqa.testRunId", "88"));
        FactoryScenario.executed = 0;
        launch(FactoryScenario.class);

        assertEquals(2, FactoryScenario.executed, "no selection, no deselection");
        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        assertNotNull(byExternalId(res, "E2E-DYN-1"));
        assertNotNull(byExternalId(res, "E2E-DYN-2"));
    }

    @Test
    void parallelExecutionKeepsStepsPerTest() {
        configure(Map.of("doqa.adapterMode", "1", "doqa.testRunId", "55"));
        launch(Map.of(
                "junit.jupiter.execution.parallel.enabled", "true",
                "junit.jupiter.execution.parallel.mode.default", "concurrent",
                "junit.jupiter.execution.parallel.mode.classes.default", "concurrent"),
                ParallelScenarioA.class, ParallelScenarioB.class);

        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        assertEquals(4, res.size(), "every test reported exactly once");
        for (String id : List.of("E2E-PAR-A1", "E2E-PAR-A2", "E2E-PAR-B1", "E2E-PAR-B2")) {
            Map<String, Object> r = byExternalId(res, id);
            assertNotNull(r, id);
            List<Map<String, Object>> steps = maps(r.get("step_results"));
            assertEquals(2, steps.size(), id + " keeps exactly its own steps");
            for (Map<String, Object> step : steps) {
                assertTrue(String.valueOf(step.get("title")).endsWith("of " + id),
                        "no step leakage between concurrent tests: " + step.get("title"));
            }
        }
    }

    @Test
    void failedBeforeAllReportsEveryDescendant() {
        configure(Map.of("doqa.adapterMode", "2"));
        launch(FailingBeforeAllScenario.class);

        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        for (String id : List.of("E2E-BA-1", "E2E-BA-2")) {
            Map<String, Object> r = byExternalId(res, id);
            assertNotNull(r, id + " synthesized from the failed container");
            assertEquals("broken", r.get("outcome"));
            assertTrue(String.valueOf(r.get("message")).contains("infra down"));
        }
    }

    @Test
    void disabledClassReportsEveryDescendantAsSkipped() {
        configure(Map.of("doqa.adapterMode", "2"));
        launch(DisabledClassScenario.class);

        Recorded results = only("POST", "/api/autotests/results");
        List<Map<String, Object>> res = maps(Json.parseObject(results.body).get("results"));
        for (String id : List.of("E2E-DIS-1", "E2E-DIS-2")) {
            Map<String, Object> r = byExternalId(res, id);
            assertNotNull(r, id + " reported from the skipped container");
            assertEquals("skipped", r.get("outcome"));
        }
    }

    // ------------------------------------------------------------------ files sink
    @Test
    void filesModeEmitsParserCompatibleAllureResultsWithoutNetwork() throws IOException {
        Path resultsDir = Files.createTempDirectory("doqa-e2e-files");
        setProp("doqa.reporting", "files");
        setProp("doqa.resultsDir", resultsDir.toString());
        DoqaSession.reset();

        launch(DemoLoginScenario.class);

        assertTrue(recorded.isEmpty(), "files sink must not touch the network");
        List<Map<String, Object>> results = readResults(resultsDir);
        assertEquals(8, results.size(), "4 tests + skipped + 2+2 parameterized invocations");

        Map<String, Object> login = byLabel(results, "E2E-LOGIN-1");
        assertNotNull(login, "login result attributed via doqa_id label");
        assertEquals("passed", login.get("status"), String.valueOf(login.get("statusDetails")));
        assertEquals("E2E-LOGIN-1", login.get("historyId"));
        Map<String, String> labels = labelMap(login.get("labels"));
        assertEquals("901", labels.get("doqa_cases"));
        assertEquals("app.doqa.e2e", labels.get("package"));

        // steps land in result.steps (parser: build_steps_tree(result.steps, fixtures))
        List<Map<String, Object>> steps = maps(login.get("steps"));
        assertEquals("open login page", steps.get(0).get("name"));
        assertEquals("annotated helper", maps(steps.get(0).get("steps")).get(0).get("name"),
                "LTW @Step child present in file emit");
        assertNotNull(steps.get(0).get("start"), "step start/stop let the parser recompute durationMs");
        assertNotNull(steps.get(0).get("stop"));

        // attachment copied next to the JSON and referenced as {name, source, type}
        Map<String, Object> att = maps(login.get("attachments")).get(0);
        assertEquals("image/png", att.get("type"));
        assertTrue(String.valueOf(att.get("name")).startsWith("doqa-e2e-shot"));
        assertTrue(Files.exists(resultsDir.resolve(String.valueOf(att.get("source")))),
                "attachment payload copied into the results dir");

        // fixtures travel via containers referencing the result uuid: the per-result container
        // carries [@BeforeAll (prepended), setUp] / [tearDown]; the shared class container
        // (children = all results) carries @AfterAll (known only at plan end).
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
        assertEquals("beforeAllInit", befores.get(0).get("name"), "@BeforeAll first");
        assertEquals("setUp", befores.get(1).get("name"));
        assertEquals("tearDown", maps(perResult.get("afters")).get(0).get("name"));
        assertNotNull(classContainer, "shared class container for @AfterAll");
        assertEquals(8, ((List<?>) classContainer.get("children")).size(),
                "class container spans every result of the class");
        assertEquals("afterAllCleanup", maps(classContainer.get("afters")).get(0).get("name"));

        // outcome mapping survives the file path too
        List<String> statuses = new ArrayList<>();
        for (Map<String, Object> r : results) {
            statuses.add(String.valueOf(r.get("status")));
        }
        assertTrue(statuses.contains("failed") && statuses.contains("broken")
                && statuses.contains("skipped"), "statuses: " + statuses);
    }

    @Test
    void autoModeFallsBackToFilesWhenApiConfigIsAbsent() throws IOException {
        Path resultsDir = Files.createTempDirectory("doqa-e2e-auto");
        // reporting=auto without url/token/spaceId must degrade to the file sink.
        setProp("doqa.reporting", "auto");
        setProp("doqa.resultsDir", resultsDir.toString());
        DoqaSession.reset();
        SelectDemoScenario.executed = 0;

        launch(SelectDemoScenario.class);

        assertTrue(recorded.isEmpty());
        assertEquals(4, SelectDemoScenario.executed, "no mode-0 selection in files mode");
        assertEquals(4, readResults(resultsDir).size());
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
                out.add(Json.parseObject(new String(Files.readAllBytes(p),
                        StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    private List<Map<String, Object>> containersFor(Path dir, String resultUuid)
            throws IOException {
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
