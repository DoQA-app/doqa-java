package app.doqa.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin HTTP client for the DoQA Autotest API. Token-auth (in the JSON body for POST, in the
 * query string for GET, per the current API contract), {@code space_id} alongside; bounded
 * retries with timeouts. Retry policy honours idempotency: GETs, 429s and idempotent POSTs
 * ({@code external_key}) are replayed on 5xx/network errors; non-idempotent POSTs are replayed
 * only when the request provably never reached the server (connect failure) - a lost response
 * must not duplicate a run or a result.
 * A circuit breaker opens after several consecutive failures and fails fast (no network call)
 * for a cooldown window, so a dead backend does not cost {@code retries × timeout} per test.
 *
 * <pre>
 * POST /api/autotests/upsert                    -&gt; {map}
 * POST /api/autotests/results                   -&gt; {accepted, elementIds}
 * POST /api/autotests/attachments  (multipart)  -&gt; {mediaFileId}
 * POST /api/autotests/test-runs                 -&gt; {runId}
 * GET  /api/autotests/test-runs/{id}/autotests  -&gt; {autotests:[{externalId}]}  (mode-0 select)
 * </pre>
 *
 * <p>{@code POST /autotests/sync-ids} (bidirectional id sync) is intentionally NOT wrapped here:
 * it is a {@code doqactl} workflow, not an adapter reporting concern - client-core stays limited
 * to the reporting surface adapters actually use.
 */
public final class ApiClient {

    /** After this many consecutive request failures the circuit opens (fail fast). */
    private static final int CIRCUIT_THRESHOLD = 5;
    /** While open, requests fail immediately; one probe is allowed after this cooldown. */
    private static final long CIRCUIT_COOLDOWN_MS = 30_000L;

    private final DoqaConfig config;
    private final String base;
    private final Transport transport;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong lastFailureAtMs = new AtomicLong();

    public ApiClient(DoqaConfig config) {
        this(config,
                new HttpClientTransport(config, Duration.ofMillis(config.requestTimeoutMs())),
                config.retries(), config.retryBackoffMs());
    }

    /** Full constructor - inject a {@link Transport} (test seam) and retry policy. */
    public ApiClient(DoqaConfig config, Transport transport, int maxRetries, long retryBackoffMs) {
        this.config = config;
        this.base = config.url() == null ? "" : stripTrailingSlash(config.url());
        this.transport = transport;
        this.maxRetries = Math.max(1, maxRetries);
        this.retryBackoffMs = retryBackoffMs;
    }

    // ---- endpoints ------------------------------------------------------

    /** POST /upsert. Returns the {@code {external_id: {...}}} map. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> upsertAutotests(Collection<AutotestDef> defs) {
        Map<String, Object> body = authBody();
        body.put("autotests", Payloads.payloads(defs));
        Transport.Response r = request(Transport.Request.postJson(url("upsert"), Json.write(body)));
        Object map = Json.parseObject(r.body).get("map");
        return map instanceof Map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /** POST /test-runs. Returns the created {@code runId}. */
    public String createTestRun(String name, String configurationId) {
        return createTestRun(name, configurationId, null);
    }

    /**
     * POST /test-runs with an optional idempotency {@code external_key}. With a key the server
     * deduplicates the create, so the request is retried like an idempotent one; without a key a
     * lost response is NOT replayed (it could create a second run).
     */
    public String createTestRun(String name, String configurationId, String externalKey) {
        Map<String, Object> body = authBody();
        Payloads.putIfPresent(body, "name", name != null ? name : config.testRunName());
        Payloads.putIfPresent(body, "configuration_id",
                configurationId != null ? configurationId : config.configurationId());
        Payloads.putIfPresent(body, "external_key", externalKey);
        // Link the run to its CI pipeline (otherwise DoQA cannot show the pipeline/jobs for a
        // run started directly from CI): echoes CI_PIPELINE_ID/DOQA_PIPELINE_ID plus the branch.
        Payloads.putIfPresent(body, "pipeline_id", config.pipelineId());
        Payloads.putIfPresent(body, "branch", config.branch());
        Payloads.putIfPresent(body, "environment", config.environment());
        Transport.Response r = request(Transport.Request.postJson(
                url("test-runs"), Json.write(body), externalKey != null));
        Object runId = Json.parseObject(r.body).get("runId");
        return runId == null ? null : String.valueOf(runId);
    }

    /**
     * GET /test-runs/{id}/autotests - the selective plan (mode 0), in the server's order.
     * {@code ciRunId} narrows it to the tests assigned to this pipeline; a run split across several
     * pipelines needs it, and servers that do not know the parameter ignore it.
     */
    @SuppressWarnings("unchecked")
    public List<PlannedAutotest> getRunAutotests(String runId, String configurationId) {
        StringBuilder u = new StringBuilder(url("test-runs/" + runId + "/autotests"));
        u.append("?token=").append(enc(config.token()));
        String conf = configurationId != null ? configurationId : config.configurationId();
        if (conf != null) {
            u.append("&configuration_id=").append(enc(conf));
        }
        if (config.ciRunId() != null) {
            u.append("&ciRunId=").append(enc(config.ciRunId()));
        }
        Transport.Response r = request(Transport.Request.get(u.toString()));
        List<PlannedAutotest> out = new ArrayList<>();
        Object arr = Json.parseObject(r.body).get("autotests");
        if (arr instanceof List) {
            for (Object entry : (List<Object>) arr) {
                if (entry instanceof Map) {
                    PlannedAutotest planned = PlannedAutotest.fromPayload((Map<String, Object>) entry);
                    if (planned != null) {
                        out.add(planned);
                    }
                }
            }
        }
        return out;
    }

    /** POST /attachments (multipart). Returns {@code mediaFileId}. Upload BEFORE results. */
    public String uploadAttachment(String path) {
        return uploadAttachment(Paths.get(path));
    }

    /**
     * Uploads a file, streaming its bytes straight from disk - the file never occupies heap, so
     * large artifacts (UI-test videos) upload without OOM risk.
     */
    public String uploadAttachment(Path path) {
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new ApiError("failed to read attachment " + path + ": " + e.getMessage(), e);
        }
        String filename = path.getFileName().toString();
        return uploadAttachment(filename, size, () -> {
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, ContentTypes.of(filename));
    }

    /** Uploads in-memory content as an attachment (no temp file needed). */
    public String uploadAttachment(String filename, byte[] content, String contentType) {
        byte[] bytes = content == null ? new byte[0] : content;
        return uploadAttachment(filename, bytes.length, () -> new ByteArrayInputStream(bytes),
                contentType == null ? ContentTypes.of(filename) : contentType);
    }

    private String uploadAttachment(String filename, long size,
                                    java.util.function.Supplier<InputStream> content,
                                    String contentType) {
        String boundary = "----DoqaBoundary" + Long.toHexString(System.nanoTime());
        byte[] preamble = Multipart.preamble(boundary, filename, contentType,
                config.token(), config.spaceId());
        byte[] epilogue = Multipart.epilogue(boundary);
        long total = preamble.length + size + epilogue.length;
        Transport.Response r = request(Transport.Request.postStream(
                url("attachments"),
                () -> new SequenceInputStream(Collections.enumeration(List.of(
                        new ByteArrayInputStream(preamble),
                        content.get(),
                        new ByteArrayInputStream(epilogue)))),
                total,
                "multipart/form-data; boundary=" + boundary));
        Object id = Json.parseObject(r.body).get("mediaFileId");
        return id == null ? null : String.valueOf(id);
    }

    /** POST /results. Returns {@code {accepted, elementIds}}. */
    public Map<String, Object> uploadResults(String runId, String configurationId,
                                             Collection<AutotestResult> results) {
        Map<String, Object> body = authBody();
        Payloads.putIfPresent(body, "test_run_id", runId);
        Payloads.putIfPresent(body, "configuration_id",
                configurationId != null ? configurationId : config.configurationId());
        // CI correlation: without it the backend cannot link results to the pipeline
        // (per-pipeline quality gate and autotest sources would see an empty run).
        Payloads.putIfPresent(body, "ci_run_id", config.ciRunId());
        Payloads.putIfPresent(body, "pipeline_id", config.pipelineId());
        body.put("results", Payloads.payloads(results));
        Transport.Response r = request(Transport.Request.postJson(url("results"), Json.write(body)));
        return Json.parseObject(r.body);
    }

    // ---- low level ------------------------------------------------------
    private Map<String, Object> authBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", config.token());
        body.put("space_id", config.spaceId());
        return body;
    }

    private String url(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return base + "/api/autotests/" + p;
    }

    private Transport.Response request(Transport.Request req) {
        int attempts = circuitAttempts(req);
        IOException last = null;
        int lastStatus = 0;
        for (int attempt = 0; attempt < attempts; attempt++) {
            Transport.Response resp;
            try {
                resp = transport.send(req);
            } catch (IOException e) {
                last = e;
                if (retriableIo(req, e) && attempt + 1 < attempts) {
                    backoff(attempt, req);
                    continue;
                }
                recordFailure();
                throw new ApiError("request to " + safeUrl(req.url) + " failed: " + e.getMessage(), e);
            }
            boolean retriableStatus = resp.status == 429 || (resp.status >= 500 && req.idempotent);
            if (retriableStatus && attempt + 1 < attempts) {
                lastStatus = resp.status;
                backoff(attempt, req);
                continue;
            }
            if (resp.status >= 400) {
                recordFailure();
                String body = resp.body.length() > 500 ? resp.body.substring(0, 500) : resp.body;
                throw new ApiError(req.method + " " + safeUrl(req.url) + " -> " + resp.status + ": " + body,
                        resp.status);
            }
            consecutiveFailures.set(0);
            return resp;
        }
        recordFailure();
        if (last != null) {
            throw new ApiError("request to " + safeUrl(req.url) + " failed after retries", last);
        }
        throw new ApiError("request to " + safeUrl(req.url) + " failed after retries (last status "
                + lastStatus + ")", lastStatus);
    }

    /**
     * Attempts allowed under the circuit state: closed =&gt; full retry budget; open within the
     * cooldown =&gt; fail fast without touching the network; open past the cooldown =&gt; one probe.
     */
    private int circuitAttempts(Transport.Request req) {
        if (consecutiveFailures.get() < CIRCUIT_THRESHOLD) {
            return maxRetries;
        }
        if (System.currentTimeMillis() - lastFailureAtMs.get() < CIRCUIT_COOLDOWN_MS) {
            throw new ApiError("request to " + safeUrl(req.url)
                    + " rejected: circuit open after " + consecutiveFailures.get()
                    + " consecutive failures");
        }
        return 1;
    }

    private void recordFailure() {
        consecutiveFailures.incrementAndGet();
        lastFailureAtMs.set(System.currentTimeMillis());
    }

    /**
     * A network error is safe to retry when the request is idempotent, or when the connection
     * was never established (nothing could have been processed server-side).
     */
    private static boolean retriableIo(Transport.Request req, IOException e) {
        return req.idempotent
                || e instanceof ConnectException
                || e instanceof HttpConnectTimeoutException;
    }

    private void backoff(int attempt, Transport.Request req) {
        try {
            Thread.sleep(retryBackoffMs << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError("request to " + safeUrl(req.url) + " interrupted during retry backoff");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new ApiError("request to " + safeUrl(req.url) + " interrupted during retry backoff");
        }
    }

    /** Token travels in the query string on GETs - never let it reach logs via error messages. */
    private static String safeUrl(String url) {
        return url == null ? null : url.replaceAll("([?&]token=)[^&]*", "$1***");
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
