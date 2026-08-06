package app.doqa.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Contract fixtures for client-core: config resolution priority, model &rarr; Autotest API JSON
 * shapes (upsert / results with parameters as a LIST), and run-selection modes 0/1/2. Uses a
 * recording {@link Transport} - no network, offline-verifiable.
 */
class ClientContractTest {

    /** A recording transport that returns canned JSON per URL suffix and captures the last body. */
    static final class FakeTransport implements Transport {
        String lastUrl;
        String lastBody;
        String lastMethod;
        final List<String> bodies = new ArrayList<>();

        @Override
        public Response send(Request request) {
            lastUrl = request.url;
            lastMethod = request.method;
            lastBody = request.jsonBody;
            if (request.jsonBody != null) {
                bodies.add(request.jsonBody);
            }
            if (request.url.endsWith("/upsert")) {
                return new Response(200, "{\"map\":{\"E-1\":{\"autotestId\":\"a1\",\"created\":true}}}");
            }
            if (request.url.contains("/test-runs/") && request.url.contains("/autotests")) {
                return new Response(200, "{\"autotests\":[{\"externalId\":\"E-1\"},{\"externalId\":\"E-3\"}]}");
            }
            if (request.url.endsWith("/test-runs")) {
                return new Response(200, "{\"runId\":\"RUN-42\"}");
            }
            if (request.url.endsWith("/results")) {
                return new Response(200, "{\"accepted\":1,\"elementIds\":[\"x\"]}");
            }
            if (request.url.endsWith("/attachments")) {
                return new Response(200, "{\"mediaFileId\":\"MF-9\"}");
            }
            return new Response(200, "{}");
        }
    }

    private static DoqaConfig cfg(int mode) {
        return new DoqaConfig.Builder()
                .url("https://doqa.example/")
                .token("TOK")
                .spaceId("SP")
                .adapterMode(mode)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    // ------------------------------------------------------------------ config
    @Test
    void configPriorityCliOverEnvOverFile() {
        Properties sys = new Properties();
        sys.setProperty("doqa.url", "https://cli/");        // CLI layer
        sys.setProperty("doqa.privateToken", "cli-token");  // alias
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DOQA_URL", "https://env/");
        env.put("DOQA_TOKEN", "env-token");
        env.put("DOQA_PROJECT_ID", "SP-env");               // alias -> spaceId
        env.put("DOQA_ADAPTER_MODE", "0");

        DoqaConfig c = ConfigResolver.resolve(sys, env, null);
        assertEquals("https://cli/", c.url());     // CLI wins over env
        assertEquals("cli-token", c.token());      // CLI alias wins
        assertEquals("SP-env", c.spaceId());       // env-only alias survives
        assertEquals(0, c.adapterMode());
        assertTrue(c.enabled());
    }

    @Test
    void configDefaults() {
        DoqaConfig c = ConfigResolver.resolve(new Properties(), Collections.emptyMap(), null);
        assertEquals(2, c.adapterMode());
        assertFalse(c.importRealtime());
        assertTrue(c.certValidation());
        assertFalse(c.enabled());  // no url/token/space
    }

    // ------------------------------------------------------------------ upsert shape
    @Test
    void upsertPayloadMatchesContract() {
        FakeTransport t = new FakeTransport();
        ApiClient client = new ApiClient(cfg(2), t, 3, 0);

        AutotestDef def = new AutotestDef("E-1", "Login works")
                .title("Login")
                .labels(Arrays.asList("smoke"))
                .links(Arrays.asList(new Link("http://bug/1", LinkType.DEFECT, null, null)))
                .steps(Arrays.asList(new Step("open", null, StepKind.STEP,
                        Arrays.asList(Step.of("child")))))
                .caseIds(Arrays.asList(101L, 102L));

        Map<String, Object> resp = client.upsertAutotests(Arrays.asList(def));
        assertTrue(resp.containsKey("E-1"));

        Map<String, Object> body = Json.parseObject(t.lastBody);
        assertEquals("TOK", body.get("token"));
        assertEquals("SP", body.get("space_id"));
        List<Object> autotests = asList(body.get("autotests"));
        Map<String, Object> a0 = asMap(autotests.get(0));
        assertEquals("E-1", a0.get("external_id"));
        assertEquals("Login works", a0.get("name"));
        assertEquals("Login", a0.get("title"));
        assertEquals(Arrays.asList("smoke"), a0.get("labels"));
        // typed link
        Map<String, Object> link0 = asMap(asList(a0.get("links")).get(0));
        assertEquals("http://bug/1", link0.get("url"));
        assertEquals("defect", link0.get("type"));
        // nested steps + kind
        Map<String, Object> step0 = asMap(asList(a0.get("steps")).get(0));
        assertEquals("open", step0.get("title"));
        assertEquals("step", step0.get("kind"));
        assertEquals("child", asMap(asList(step0.get("steps")).get(0)).get("title"));
        // case ids (ints)
        assertEquals(Arrays.asList(101L, 102L), a0.get("case_ids"));
    }

    // ------------------------------------------------------------------ results shape
    @Test
    void resultsPayloadParametersAsListAndNestedSteps() {
        FakeTransport t = new FakeTransport();
        ApiClient client = new ApiClient(cfg(1), t, 3, 0);

        StepResult child = new StepResult("assert", Outcome.PASSED, 5L, null, null, null);
        StepResult top = new StepResult("do", Outcome.PASSED, 12L, null,
                Arrays.asList(new Attachment("MF-1")), Arrays.asList(child));

        AutotestResult result = new AutotestResult("E-1", Outcome.FAILED)
                .name("Login works")
                .startedOn(1000L).completedOn(1200L).durationMs(200L)
                .message("boom").traces("stack")
                .parameters(Arrays.asList(new Parameter("browser", "chrome"),
                        new Parameter("retries", 0)))
                .stepResults(Arrays.asList(top))
                .setupResults(Arrays.asList(new StepResult("db", Outcome.PASSED, 1L, null, null, null)))
                .attachments(Arrays.asList(new Attachment("MF-2")))
                .links(Arrays.asList(new Link("http://x", (String) null, null, null)))
                .createManualCase(true);

        client.uploadResults("RUN-1", "CFG-1", Arrays.asList(result));

        Map<String, Object> body = Json.parseObject(t.lastBody);
        assertEquals("RUN-1", body.get("test_run_id"));
        assertEquals("CFG-1", body.get("configuration_id"));
        Map<String, Object> r0 = asMap(asList(body.get("results")).get(0));
        assertEquals("E-1", r0.get("external_id"));
        assertEquals("failed", r0.get("outcome"));
        assertEquals("boom", r0.get("message"));
        assertEquals(true, r0.get("create_manual_case"));
        // parameters is a LIST of {name,value}
        List<Object> params = asList(r0.get("parameters"));
        assertEquals("browser", asMap(params.get(0)).get("name"));
        assertEquals("chrome", asMap(params.get(0)).get("value"));
        assertEquals("retries", asMap(params.get(1)).get("name"));
        assertEquals(0L, asMap(params.get(1)).get("value"));  // falsy scalar kept
        // nested step_results + attachments (media_file_id)
        Map<String, Object> s0 = asMap(asList(r0.get("step_results")).get(0));
        assertEquals("do", s0.get("title"));
        assertEquals("MF-1", asMap(asList(s0.get("attachments")).get(0)).get("media_file_id"));
        assertEquals("assert", asMap(asList(s0.get("steps")).get(0)).get("title"));
        // setup_results present
        assertEquals("db", asMap(asList(r0.get("setup_results")).get(0)).get("title"));
    }

    @Test
    void resultsEchoCiCorrelationWhenConfigured() {
        // Echo of DOQA_CI_RUN_ID / CI_PIPELINE_ID: the backend links results to the exact
        // pipeline (per-pipeline quality gate, autotest sources). Absent -> keys omitted.
        FakeTransport t = new FakeTransport();
        DoqaConfig c = new DoqaConfig.Builder()
                .url("https://doqa.example/").token("TOK").spaceId("SP").adapterMode(1)
                .ciRunId("42").pipelineId("65781")
                .build();
        ApiClient client = new ApiClient(c, t, 3, 0);

        client.uploadResults("RUN-1", null, Arrays.asList(new AutotestResult("E-1", Outcome.PASSED)));

        Map<String, Object> body = Json.parseObject(t.lastBody);
        assertEquals("42", body.get("ci_run_id"));
        assertEquals("65781", body.get("pipeline_id"));
    }

    @Test
    void ciRunIdResolvedFromDoqaEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DOQA_CI_RUN_ID", "42");
        DoqaConfig c = ConfigResolver.resolve(new Properties(), env, null);
        assertEquals("42", c.ciRunId());
    }

    @Test
    void compactDropsEmptyKeepsFalsyScalars() {
        // A def with no optional fields -> only external_id + name survive.
        Map<String, Object> p = new AutotestDef("E-2", "bare").toPayload();
        assertEquals("E-2", p.get("external_id"));
        assertEquals("bare", p.get("name"));
        assertFalse(p.containsKey("labels"));   // empty list dropped
        assertFalse(p.containsKey("title"));    // null dropped
    }

    // ------------------------------------------------------------------ attachments
    @Test
    void uploadAttachmentReturnsMediaFileId() throws Exception {
        FakeTransport t = new FakeTransport();
        ApiClient client = new ApiClient(cfg(2), t, 3, 0);
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("doqa", ".txt");
        java.nio.file.Files.write(tmp, "hello".getBytes());
        String id = client.uploadAttachment(tmp);
        assertEquals("MF-9", id);
        assertTrue(t.lastUrl.endsWith("/attachments"));
        java.nio.file.Files.deleteIfExists(tmp);
    }

    // ------------------------------------------------------------------ modes 0/1/2
    @Test
    void mode2CreatesRunAndSelectsNothing() {
        FakeTransport t = new FakeTransport();
        ApiClient client = new ApiClient(cfg(2), t, 3, 0);
        RunContext ctx = RunContext.establish(client, cfg(2));
        assertEquals("RUN-42", ctx.runId());
        assertNull(ctx.selectedExternalIds());
        assertTrue(ctx.allows("anything"));
    }

    @Test
    void mode0FetchesSelectiveExternalIds() {
        FakeTransport t = new FakeTransport();
        DoqaConfig c = new DoqaConfig.Builder().url("https://x/").token("T").spaceId("S")
                .adapterMode(0).testRunId("RUN-9").build();
        ApiClient client = new ApiClient(c, t, 3, 0);
        RunContext ctx = RunContext.establish(client, c);
        assertEquals("RUN-9", ctx.runId());
        assertTrue(ctx.allows("E-1"));
        assertTrue(ctx.allows("E-3"));
        assertFalse(ctx.allows("E-2"));  // not in selective list
    }

    @Test
    void mode0PreservesServerOrder() {
        // the selective list is ORDERED by the server (plan order) - the client must preserve it.
        Transport ordered = request -> new Transport.Response(200,
                "{\"ordered\":true,\"autotests\":[{\"externalId\":\"E-3\",\"position\":1},"
                        + "{\"externalId\":\"E-1\",\"position\":2},{\"externalId\":\"E-2\",\"position\":3}]}");
        DoqaConfig c = new DoqaConfig.Builder().url("https://x/").token("T").spaceId("S")
                .adapterMode(0).testRunId("RUN-9").build();
        ApiClient client = new ApiClient(c, ordered, 3, 0);
        RunContext ctx = RunContext.establish(client, c);
        assertEquals(java.util.Arrays.asList("E-3", "E-1", "E-2"), ctx.selectedOrder());
        assertEquals(0, ctx.orderIndex("E-3"));
        assertEquals(1, ctx.orderIndex("E-1"));
        assertEquals(2, ctx.orderIndex("E-2"));
        // not in the plan -> MAX_VALUE (stable-sort tail, never dropped)
        assertEquals(Integer.MAX_VALUE, ctx.orderIndex("E-404"));
        // back-compat: membership semantics unchanged
        assertTrue(ctx.allows("E-1"));
        assertFalse(ctx.allows("E-404"));
    }

    @Test
    void orderIndexWithoutPlanIsMaxValue() {
        DoqaConfig c = new DoqaConfig.Builder().url("https://x/").token("T").spaceId("S")
                .adapterMode(1).testRunId("RUN-7").build();
        RunContext ctx = RunContext.establish(new ApiClient(c, new FakeTransport(), 3, 0), c);
        assertNull(ctx.selectedOrder());
        assertEquals(Integer.MAX_VALUE, ctx.orderIndex("anything"));
    }

    @Test
    void mode1UsesExistingRunNoSelection() {
        DoqaConfig c = new DoqaConfig.Builder().url("https://x/").token("T").spaceId("S")
                .adapterMode(1).testRunId("RUN-7").build();
        ApiClient client = new ApiClient(c, new FakeTransport(), 3, 0);
        RunContext ctx = RunContext.establish(client, c);
        assertEquals("RUN-7", ctx.runId());
        assertNull(ctx.selectedExternalIds());
    }

    // ------------------------------------------------------------------ retries
    @Test
    void idempotentPostRetriesOn5xxThenSucceeds() {
        // external_key makes the create idempotent server-side => the client may replay it.
        final int[] calls = {0};
        Transport flaky = request -> {
            calls[0]++;
            if (calls[0] == 1) {
                return new Transport.Response(503, "busy");
            }
            return new Transport.Response(200, "{\"runId\":\"OK\"}");
        };
        ApiClient client = new ApiClient(cfg(2), flaky, 3, 0);
        assertEquals("OK", client.createTestRun("n", null, "key-1"));
        assertEquals(2, calls[0]);
    }

    @Test
    void nonIdempotentPostIsNotRetriedOn5xx() {
        // A 5xx may have been produced AFTER the server applied the write - replaying a
        // non-idempotent POST could duplicate a run/result, so it must fail on the first hit.
        final int[] calls = {0};
        Transport failing = request -> {
            calls[0]++;
            return new Transport.Response(502, "bad gateway");
        };
        ApiClient client = new ApiClient(cfg(1), failing, 3, 0);
        assertThrows(ApiError.class, () -> client.uploadResults("RUN-1", null,
                Arrays.asList(new AutotestResult("E-1", Outcome.PASSED))));
        assertEquals(1, calls[0]);
    }

    @Test
    void tooManyRequestsIsRetriedEvenForNonIdempotentPost() {
        // 429 == "not processed" - always safe to retry.
        final int[] calls = {0};
        Transport throttled = request -> {
            calls[0]++;
            if (calls[0] == 1) {
                return new Transport.Response(429, "slow down");
            }
            return new Transport.Response(200, "{\"accepted\":1}");
        };
        ApiClient client = new ApiClient(cfg(1), throttled, 3, 0);
        client.uploadResults("RUN-1", null, Arrays.asList(new AutotestResult("E-1", Outcome.PASSED)));
        assertEquals(2, calls[0]);
    }

    @Test
    void exhaustedRetriesThrowApiErrorWithCause() {
        final int[] calls = {0};
        Transport dead = request -> {
            calls[0]++;
            throw new java.net.ConnectException("refused");
        };
        ApiClient client = new ApiClient(cfg(2), dead, 2, 0);
        ApiError e = assertThrows(ApiError.class,
                () -> client.getRunAutotests("RUN-1", null));
        assertTrue(e.getMessage().contains("failed"));
        assertTrue(e.getCause() instanceof java.net.ConnectException);
        assertEquals(2, calls[0]);
    }

    @Test
    void connectFailureRetriesEvenNonIdempotentPost() {
        // Connection never established => nothing reached the server => replay is safe.
        final int[] calls = {0};
        Transport flaky = request -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new java.net.ConnectException("refused");
            }
            return new Transport.Response(200, "{\"accepted\":1}");
        };
        ApiClient client = new ApiClient(cfg(1), flaky, 3, 0);
        client.uploadResults("RUN-1", null, Arrays.asList(new AutotestResult("E-1", Outcome.PASSED)));
        assertEquals(2, calls[0]);
    }

    @Test
    void clientErrorFailsFastWithTruncatedBody() {
        final int[] calls = {0};
        String longBody = "x".repeat(2000);
        Transport rejecting = request -> {
            calls[0]++;
            return new Transport.Response(400, longBody);
        };
        ApiClient client = new ApiClient(cfg(1), rejecting, 3, 0);
        ApiError e = assertThrows(ApiError.class,
                () -> client.uploadResults("RUN-1", null,
                        Arrays.asList(new AutotestResult("E-1", Outcome.PASSED))));
        assertEquals(1, calls[0], "4xx must not be retried");
        assertTrue(e.getMessage().length() < 700, "response body truncated in the error message");
    }

    // ------------------------------------------------------------------ circuit breaker
    @Test
    void circuitOpensAfterConsecutiveFailuresAndFailsFast() {
        final int[] calls = {0};
        Transport dead = request -> {
            calls[0]++;
            return new Transport.Response(500, "down");
        };
        ApiClient client = new ApiClient(cfg(1), dead, 1, 0);
        for (int i = 0; i < 5; i++) {
            assertThrows(ApiError.class, () -> client.getRunAutotests("R", null));
        }
        assertEquals(5, calls[0]);
        // circuit is open now: the next request fails WITHOUT touching the network
        ApiError e = assertThrows(ApiError.class, () -> client.getRunAutotests("R", null));
        assertTrue(e.getMessage().contains("circuit open"), e.getMessage());
        assertEquals(5, calls[0], "no network call while the circuit is open");
    }

    @Test
    void successBelowThresholdResetsFailureCount() {
        final int[] calls = {0};
        final boolean[] fail = {true};
        Transport flaky = request -> {
            calls[0]++;
            return fail[0] ? new Transport.Response(500, "down")
                    : new Transport.Response(200, "{\"autotests\":[]}");
        };
        ApiClient client = new ApiClient(cfg(1), flaky, 1, 0);
        for (int i = 0; i < 4; i++) {
            assertThrows(ApiError.class, () -> client.getRunAutotests("R", null));
        }
        fail[0] = false;
        client.getRunAutotests("R", null);  // success resets the counter
        fail[0] = true;
        assertThrows(ApiError.class, () -> client.getRunAutotests("R", null));
        assertEquals(6, calls[0], "circuit stayed closed - every call reached the transport");
    }

    // ------------------------------------------------------------------ error hygiene
    @Test
    void tokenNeverAppearsInErrorMessages() {
        Transport rejecting = request -> new Transport.Response(404, "not found");
        ApiClient client = new ApiClient(cfg(1), rejecting, 1, 0);
        ApiError e = assertThrows(ApiError.class, () -> client.getRunAutotests("RUN-1", null));
        assertFalse(e.getMessage().contains("TOK"), "token must be masked: " + e.getMessage());
        assertTrue(e.getMessage().contains("token=***"), e.getMessage());
    }

    // ------------------------------------------------------------------ JSON edge cases
    @Test
    void nonFiniteNumbersSerializeAsNull() {
        assertEquals("{\"a\":null,\"b\":null,\"c\":1.5}", Json.write(new LinkedHashMap<>(Map.of()) {{
            put("a", Double.NaN);
            put("b", Double.POSITIVE_INFINITY);
            put("c", 1.5);
        }}));
    }

    // ------------------------------------------------------------------ config: files & layers
    @Test
    void configFileIsReadAsUtf8(@TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path file = tmp.resolve("doqa.properties");
        java.nio.file.Files.write(file,
                "testRunName=Прогон смоук-тестов\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DoqaConfig c = ConfigResolver.resolve(new Properties(), Collections.emptyMap(), file);
        assertEquals("Прогон смоук-тестов", c.testRunName());
    }

    @Test
    void explicitEmptyCliValueClearsInheritedSetting() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CI_PIPELINE_ID", "12345");
        Properties sys = new Properties();
        sys.setProperty("doqa.pipelineId", "");
        DoqaConfig c = ConfigResolver.resolve(sys, env, null);
        assertNull(c.pipelineId(), "explicit empty CLI value must clear the auto-picked one");
        // without the override the CI variable is picked up
        assertEquals("12345", ConfigResolver.resolve(new Properties(), env, null).pipelineId());
    }

    @Test
    void emptyEnvValuesAreIgnored() {
        // CI systems export empty vars for unset settings - they must not clear anything.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DOQA_TOKEN", "");
        Properties sys = new Properties();
        DoqaConfig c = ConfigResolver.resolve(sys, env, null);
        assertNull(c.token());
    }

    @Test
    void adapterModeAcceptsSymbolicNames() {
        Properties sys = new Properties();
        sys.setProperty("doqa.adapterMode", "selective");
        assertEquals(DoqaConfig.MODE_SELECTIVE,
                ConfigResolver.resolve(sys, Collections.emptyMap(), null).adapterMode());
        sys.setProperty("doqa.adapterMode", "existing");
        assertEquals(DoqaConfig.MODE_EXISTING_RUN,
                ConfigResolver.resolve(sys, Collections.emptyMap(), null).adapterMode());
        sys.setProperty("doqa.adapterMode", "new");
        assertEquals(DoqaConfig.MODE_NEW_RUN,
                ConfigResolver.resolve(sys, Collections.emptyMap(), null).adapterMode());
    }

    @Test
    void transportTuningKeysResolve() {
        Properties sys = new Properties();
        sys.setProperty("doqa.requestTimeoutMs", "5000");
        sys.setProperty("doqa.retries", "5");
        sys.setProperty("doqa.retryBackoffMs", "100");
        sys.setProperty("doqa.batchSize", "50");
        sys.setProperty("doqa.maxTraceLength", "1234");
        DoqaConfig c = ConfigResolver.resolve(sys, Collections.emptyMap(), null);
        assertEquals(5000L, c.requestTimeoutMs());
        assertEquals(5, c.retries());
        assertEquals(100L, c.retryBackoffMs());
        assertEquals(50, c.batchSize());
        assertEquals(1234, c.maxTraceLength());
        // defaults when unset
        DoqaConfig d = ConfigResolver.resolve(new Properties(), Collections.emptyMap(), null);
        assertEquals(DoqaConfig.DEFAULT_REQUEST_TIMEOUT_MS, d.requestTimeoutMs());
        assertEquals(DoqaConfig.DEFAULT_BATCH_SIZE, d.batchSize());
    }

    // ------------------------------------------------------------------ run establishment
    @Test
    void configuredTestRunIdWithoutExplicitModeReportsIntoThatRun() {
        // The dangerous silent path: testRunId set, mode left at the default (2) - results used
        // to leave for a freshly created run. Now the id wins: no create, mode 1 semantics.
        FakeTransport t = new FakeTransport();
        DoqaConfig c = new DoqaConfig.Builder().url("https://x/").token("T").spaceId("S")
                .testRunId("RUN-55").build();
        RunContext ctx = RunContext.establish(new ApiClient(c, t, 3, 0), c);
        assertEquals("RUN-55", ctx.runId());
        assertEquals(DoqaConfig.MODE_EXISTING_RUN, ctx.mode());
        assertNull(t.lastUrl, "no HTTP call needed to establish an existing run");
    }

    @Test
    void mode2EstablishSendsIdempotencyExternalKey() {
        FakeTransport t = new FakeTransport();
        DoqaConfig c = cfg(2);
        RunContext.establish(new ApiClient(c, t, 3, 0), c);
        Map<String, Object> body = Json.parseObject(t.lastBody);
        assertTrue(String.valueOf(body.get("external_key")).startsWith("doqa-client-"),
                "create-run must carry a per-process external_key");
    }

    @Test
    void establishFailsFastWhenCreateReturnsNoRunId() {
        Transport empty = request -> new Transport.Response(200, "{}");
        DoqaConfig c = cfg(2);
        assertThrows(ApiError.class, () -> RunContext.establish(new ApiClient(c, empty, 3, 0), c));
    }

    @Test
    void environmentTravelsInCreateRun() {
        FakeTransport t = new FakeTransport();
        DoqaConfig c = new DoqaConfig.Builder().url("https://x/").token("T").spaceId("S")
                .adapterMode(2).environment("staging").build();
        RunContext.establish(new ApiClient(c, t, 3, 0), c);
        assertEquals("staging", Json.parseObject(t.lastBody).get("environment"));
    }

    // ------------------------------------------------------------------ attachments
    @Test
    void uploadAttachmentFromBytesStreamsMultipartWithContentType() throws Exception {
        final String[] captured = {null, null};
        Transport recording = request -> {
            captured[0] = request.contentType;
            try (java.io.InputStream in = request.bodyStream.get()) {
                captured[1] = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return new Transport.Response(200, "{\"mediaFileId\":\"MF-77\"}");
        };
        ApiClient client = new ApiClient(cfg(1), recording, 3, 0);
        String id = client.uploadAttachment("report.json", "{\"ok\":true}".getBytes(), null);
        assertEquals("MF-77", id);
        assertTrue(captured[0].startsWith("multipart/form-data; boundary="));
        assertTrue(captured[1].contains("filename=\"report.json\""));
        assertTrue(captured[1].contains("Content-Type: application/json"),
                "content type inferred from the file name");
        assertTrue(captured[1].contains("{\"ok\":true}"));
        assertTrue(captured[1].trim().endsWith("--"), "closing boundary present");
    }
}
