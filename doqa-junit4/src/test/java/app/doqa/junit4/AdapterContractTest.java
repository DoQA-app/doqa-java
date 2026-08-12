package app.doqa.junit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.doqa.Doqa;
import app.doqa.client.ApiClient;
import app.doqa.client.AutotestDef;
import app.doqa.client.AutotestResult;
import app.doqa.client.DoqaConfig;
import app.doqa.client.Json;
import app.doqa.client.LinkType;
import app.doqa.client.Outcome;
import app.doqa.client.Transport;
import app.doqa.core.AdapterRuntime;
import app.doqa.core.DoqaContexts;
import app.doqa.core.DoqaSession;
import app.doqa.core.ResultBuilder;
import app.doqa.core.RuntimeContext;
import app.doqa.core.TestRef;
import app.doqa.junit4.fixtures.SampleAnnotatedTest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Contract fixtures for the thick JUnit 4 adapter: projection of annotations + runtime state +
 * steps + fixtures into contract-A upsert/results JSON, and the DoQA session report/flush
 * pipeline (modes 2 &amp; 0, chunking, snapshot semantics, realtime per-class streaming) via a
 * recording transport. Attribution-cascade unit fixtures live in doqa-java-commons. No runner is
 * built and no test run is executed.
 */
public class AdapterContractTest {

    @BeforeClass
    public static void configureRuntime() {
        AdapterRuntime.configure("junit4", "junit4");
    }

    @After
    public void cleanup() {
        DoqaSession.reset();
        DoqaSession.setClientFactory(null);
        DoqaSession.setConfigOverride(null);
        AdapterState.resetAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    private static TestRef refFor(String method, String displayName, boolean parameterized)
            throws NoSuchMethodException {
        Method m = SampleAnnotatedTest.class.getDeclaredMethod(method);
        return new TestRef("app.doqa.junit4.fixtures.SampleAnnotatedTest", method, "",
                displayName, parameterized, SampleAnnotatedTest.class, m);
    }

    private static ResultBuilder.Built built(AutotestDef def, AutotestResult result) {
        return new ResultBuilder.Built(def, result, null, null, null, "fixture#method");
    }

    // ------------------------------------------------------------------ upsert + result shape
    @Test
    public void buildProjectsAnnotationsRuntimeStepsFixturesIntoContract() throws Exception {
        TestRef ref = refFor("loginWorks", "login happy path", false);
        ResultBuilder.Built built;
        try {
            RuntimeContext ctx = DoqaContexts.open("uid-1");
            ctx.testRef = ref;
            ctx.tStart = 1000L;

            // runtime add*
            Doqa.addParameter("browser", "chrome");
            Doqa.addLabels("runtime-label");
            Doqa.addLink("http://runtime", LinkType.RELATED);

            // fixture -> setup step
            ctx.phase = RuntimeContext.Phase.SETUP;
            Doqa.step("dbFixture");

            // call -> nested steps + a step attachment
            ctx.phase = RuntimeContext.Phase.CALL;
            Doqa.step("open", () -> {
                Doqa.addAttachments("shot.png");
                Doqa.step("inner", () -> { });
            });
            ctx.tEnd = 1200L;

            built = ResultBuilder.build(ctx, "passed", null, null, a -> "MF-" + a, null, null);
        } finally {
            DoqaContexts.remove("uid-1");
        }

        // round-trip through JSON
        Map<String, Object> def = Json.parseObject(Json.write(built.def.toPayload()));
        Map<String, Object> res = Json.parseObject(Json.write(built.result.toPayload()));

        // ---- def (upsert) ----
        assertEquals("DOQA-42", def.get("external_id"));
        assertEquals("login happy path", def.get("name"));    // @DoqaDisplayName
        assertEquals("Login works", def.get("title"));
        assertEquals("verifies the happy login path", def.get("description"));
        assertEquals("app.doqa.junit4.fixtures", def.get("namespace"));  // package fallback
        assertEquals("SampleAnnotatedTest", def.get("classname"));        // @DoqaClassName
        List<Object> labels = asList(def.get("labels"));
        assertTrue(labels.contains("regression"));  // class-level
        assertTrue(labels.contains("smoke"));       // method-level
        assertTrue(labels.contains("runtime-label")); // runtime
        assertEquals(Arrays.asList("ui"), def.get("tags"));
        assertEquals(Arrays.asList(101L, 102L), def.get("case_ids"));
        // links: annotation defect + runtime related
        List<Object> links = asList(def.get("links"));
        assertEquals(2, links.size());
        assertEquals("defect", asMap(links.get(0)).get("type"));
        assertEquals("related", asMap(links.get(1)).get("type"));
        // def steps: setup(before) + call(step) with nested child
        List<Object> steps = asList(def.get("steps"));
        assertEquals("dbFixture", asMap(steps.get(0)).get("title"));
        assertEquals("before", asMap(steps.get(0)).get("kind"));
        assertEquals("open", asMap(steps.get(1)).get("title"));
        assertEquals("step", asMap(steps.get(1)).get("kind"));
        assertEquals("inner", asMap(asList(asMap(steps.get(1)).get("steps")).get(0)).get("title"));

        // ---- result ----
        assertEquals("DOQA-42", res.get("external_id"));
        assertEquals("passed", res.get("outcome"));
        assertEquals(1000L, res.get("started_on"));
        assertEquals(1200L, res.get("completed_on"));
        assertEquals(200L, res.get("duration_ms"));
        // parameters list
        List<Object> params = asList(res.get("parameters"));
        assertEquals("browser", asMap(params.get(0)).get("name"));
        assertEquals("chrome", asMap(params.get(0)).get("value"));
        // step_results (call only) + step attachment media_file_id
        List<Object> stepResults = asList(res.get("step_results"));
        assertEquals("open", asMap(stepResults.get(0)).get("title"));
        assertEquals("MF-shot.png",
                asMap(asList(asMap(stepResults.get(0)).get("attachments")).get(0)).get("media_file_id"));
        // setup_results carries the fixture
        assertEquals("dbFixture", asMap(asList(res.get("setup_results")).get(0)).get("title"));
    }

    @Test
    public void parameterizedCollapsesToMethodLevelAndCarriesArgs() throws Exception {
        // the JUnit 4 shape: TestRefs turns "loginWorks[0: browser=chrome]" into the method-level
        // identity plus one "arguments" invocation parameter.
        RuntimeContext ctx = new RuntimeContext("uid-p");
        ctx.testRef = refFor("loginWorks", "loginWorks[0: browser=chrome]", true);
        ctx.tStart = 1L;
        ctx.tEnd = 2L;
        ctx.invocationParameters.addAll(
                TestRefs.invocationParameters("loginWorks[0: browser=chrome]"));
        ResultBuilder.Built built = ResultBuilder.build(ctx, "passed", null, null,
                a -> null, null, null);
        Map<String, Object> res = built.result.toPayload();
        // externalId is explicit here (DOQA-42) - collapse matters for the fallback path; args land as a param
        List<Object> params = asList(res.get("parameters"));
        assertEquals("arguments", asMap(params.get(0)).get("name"));
        assertEquals("browser=chrome", asMap(params.get(0)).get("value"));
    }

    // ------------------------------------------------------------------ session (modes 2 & 0)
    /** Recording transport with canned responses per endpoint suffix. */
    static final class FakeTransport implements Transport {
        final List<String> bodies = new ArrayList<>();
        volatile boolean failResults;

        @Override
        public Response send(Request request) {
            if (request.jsonBody != null) {
                bodies.add(request.jsonBody);
            }
            if (request.url.endsWith("/test-runs")) {
                return new Response(200, "{\"runId\":\"RUN-1\"}");
            }
            if (request.url.contains("/autotests") && request.url.contains("/test-runs/")) {
                return new Response(200, "{\"autotests\":[{\"externalId\":\"DOQA-42\"}]}");
            }
            if (request.url.endsWith("/upsert")) {
                return new Response(200, "{\"map\":{}}");
            }
            if (request.url.endsWith("/results")) {
                return failResults ? new Response(500, "boom") : new Response(200, "{\"accepted\":1}");
            }
            return new Response(200, "{}");
        }

        long count(String needle) {
            long hits = 0;
            for (String body : bodies) {
                if (body.contains(needle)) {
                    hits++;
                }
            }
            return hits;
        }

        boolean any(String needle) {
            return count(needle) > 0;
        }
    }

    private static DoqaSession session(FakeTransport t, DoqaConfig cfg) {
        // the OUTER surefire run may leave a disabled session behind
        DoqaSession.reset();
        DoqaSession.setConfigOverride(cfg);
        DoqaSession.setClientFactory(c -> new ApiClient(c, t, 1, 0));
        return DoqaSession.getOrInit();
    }

    @Test
    public void mode2SessionCreatesRunBuffersAndFlushes() {
        FakeTransport t = new FakeTransport();
        DoqaSession session = session(t, new DoqaConfig.Builder()
                .url("https://x/").token("T").spaceId("S").adapterMode(2).build());
        assertTrue(session.enabled);
        assertEquals("RUN-1", session.runContext.runId());

        session.report(built(new AutotestDef("DOQA-42", "n"),
                new AutotestResult("DOQA-42", Outcome.PASSED)));
        // batch mode: nothing uploaded until flush
        assertFalse(t.any("results"));
        session.flush();
        assertTrue(t.any("\"autotests\""));  // upsert body
        // results uploaded (test_run_id present)
        assertTrue(t.any("\"test_run_id\":\"RUN-1\""));
    }

    @Test
    public void mode0SessionSelectsExternalIds() {
        FakeTransport t = new FakeTransport();
        DoqaSession session = session(t, new DoqaConfig.Builder()
                .url("https://x/").token("T").spaceId("S").adapterMode(0).testRunId("RUN-9").build());
        assertNotNull(session.runContext.selectedExternalIds());
        assertTrue(session.runContext.allows("DOQA-42"));
        assertFalse(session.runContext.allows("DOQA-999"));

        // a deselected result is not buffered/uploaded
        session.report(built(new AutotestDef("DOQA-999", "n"),
                new AutotestResult("DOQA-999", Outcome.PASSED)));
        session.flush();
        assertFalse(t.any("DOQA-999"));
    }

    @Test
    public void batchFlushChunksAndDeduplicatesDefs() {
        FakeTransport t = new FakeTransport();
        DoqaSession session = session(t, new DoqaConfig.Builder()
                .url("https://x/").token("T").spaceId("S").adapterMode(2).batchSize(2).build());
        // 3 invocations of one parameterized test (same def id) + 2 other tests = 5 results
        for (int i = 0; i < 3; i++) {
            session.report(built(new AutotestDef("PARAM-1", "p"),
                    new AutotestResult("PARAM-1", Outcome.PASSED)));
        }
        session.report(built(new AutotestDef("T-1", "a"), new AutotestResult("T-1", Outcome.PASSED)));
        session.report(built(new AutotestDef("T-2", "b"), new AutotestResult("T-2", Outcome.PASSED)));
        session.flush();

        // defs deduped: 3 unique ids -> 2 upsert chunks of <=2; results: 5 -> 3 chunks of <=2
        assertEquals("upsert chunks", 2, t.count("\"autotests\""));
        assertEquals("result chunks", 3, t.count("\"results\""));
        long paramDefs = 0;
        for (String body : t.bodies) {
            if (body.contains("\"autotests\"")) {
                paramDefs += countOccurrences(body, "PARAM-1");
            }
        }
        assertEquals("one def per externalId in upsert payloads", 1, paramDefs);
    }

    @Test
    public void failedFlushLosesOnlyItselfAndNeverResends() {
        FakeTransport t = new FakeTransport();
        DoqaSession session = session(t, new DoqaConfig.Builder()
                .url("https://x/").token("T").spaceId("S").adapterMode(2).build());
        session.report(built(new AutotestDef("T-1", "a"), new AutotestResult("T-1", Outcome.PASSED)));
        t.failResults = true;
        session.flush();  // results chunk fails -> logged, buffer already detached
        t.failResults = false;
        session.flush();  // nothing left: no double-merge, no re-send
        assertEquals("failed chunk is not replayed by a later flush", 1, t.count("\"results\""));
    }

    @Test
    public void realtimeStreamsPerClassKeepingTeardown() {
        FakeTransport t = new FakeTransport();
        DoqaSession session = session(t, new DoqaConfig.Builder()
                .url("https://x/").token("T").spaceId("S").adapterMode(2).importRealtime(true).build());
        session.report(new ResultBuilder.Built(new AutotestDef("RT-1", "a"),
                new AutotestResult("RT-1", Outcome.PASSED), null, null, "com.x.SuiteOne",
                "com.x.SuiteOne#a"));
        session.report(new ResultBuilder.Built(new AutotestDef("RT-2", "b"),
                new AutotestResult("RT-2", Outcome.PASSED), null, null, "com.x.SuiteOne",
                "com.x.SuiteOne#b"));
        // nothing sent until the class container finishes
        assertEquals(0, t.count("\"results\""));
        session.flushClass("com.x.SuiteOne");
        assertEquals("one results POST per finished class", 1, t.count("\"results\""));
        // plan-end flush has nothing left for that class
        session.flush();
        assertEquals(1, t.count("\"results\""));
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
