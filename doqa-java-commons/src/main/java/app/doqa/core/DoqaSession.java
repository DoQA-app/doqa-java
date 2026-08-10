package app.doqa.core;

import app.doqa.client.AllureFileWriter;
import app.doqa.client.ApiClient;
import app.doqa.client.ApiError;
import app.doqa.client.AutotestDef;
import app.doqa.client.AutotestResult;
import app.doqa.client.ConfigResolver;
import app.doqa.client.DoqaConfig;
import app.doqa.client.RunContext;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Process-wide lazy singleton holding the resolved config and the active reporting sink:
 * <ul>
 *   <li><b>api</b>: {@link ApiClient} + {@link RunContext} (modes 0/1/2). Batch mode buffers the
 *       whole plan and flushes at plan end in {@code batchSize} chunks (one failed chunk loses
 *       that chunk, never the run); realtime mode streams per top-level test class as soon as its
 *       container (incl. {@code @AfterAll}) finishes, so class teardown is never lost;</li>
 *   <li><b>files</b>: {@link AllureFileWriter} emitting parser-compatible Allure results
 *       (no network, no credentials: the CI-artifact path);</li>
 *   <li><b>auto</b> (default): api when url/token/space are configured, files otherwise;</li>
 *   <li><b>off</b>: reporting disabled.</li>
 * </ul>
 * A JVM shutdown hook performs a best-effort flush, so results survive a test calling
 * {@code System.exit} or a CI kill between the last test and plan end.
 *
 * <p>Internal adapter API - shared by the framework glue (listener / discovery filter / orderers).
 */
public final class DoqaSession {

    private static final Logger LOG = Logger.getLogger(DoqaSession.class.getName());

    private static final String CONFIG_HINT =
            "Configure them via -Ddoqa.url / -Ddoqa.token / -Ddoqa.spaceId, "
                    + "the DOQA_URL / DOQA_TOKEN / DOQA_SPACE_ID environment variables, "
                    + "or a doqa.properties file.";

    // Test seams (injectable before first use).
    private static volatile Function<DoqaConfig, ApiClient> clientFactory;
    private static volatile DoqaConfig configOverride;
    private static volatile Map<String, String> envOverride;

    private static volatile DoqaSession instance;
    /** Cached {@link #discoverySelectionActive()} verdict; the filter asks per descriptor. */
    private static volatile Boolean discoveryVerdict;
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();

    public final boolean enabled;
    public final DoqaConfig config;
    public final ApiClient client;              // null unless api sink
    public final RunContext runContext;         // null unless api sink
    public final AllureFileWriter fileWriter;   // null unless files sink
    public final boolean realtime;

    /** One buffered report; keeps the class link for fixture merging at flush. */
    private static final class Reported {
        final AutotestDef def;
        final AutotestResult result;
        final String classKey;

        Reported(AutotestDef def, AutotestResult result, String classKey) {
            this.def = def;
            this.result = result;
            this.classKey = classKey;
        }
    }

    private final List<Reported> buffer = new ArrayList<>();
    /** realtime: reports per TOP-LEVEL class, streamed when the class container finishes. */
    private final Map<String, List<Reported>> realtimeByClass = new LinkedHashMap<>();
    /** files sink: result uuids per test class (children of the shared @AfterAll container). */
    private final Map<String, List<String>> fileUuidsByClass = new LinkedHashMap<>();
    /** externalId -> first reporting methodKey; a second method on the same id gets a warning. */
    private final Map<String, String> idOwners = new ConcurrentHashMap<>();
    private final Set<String> duplicateIdsWarned = ConcurrentHashMap.newKeySet();

    private DoqaSession(boolean enabled, DoqaConfig config, ApiClient client,
                        RunContext runContext, AllureFileWriter fileWriter) {
        this.enabled = enabled;
        this.config = config;
        this.client = client;
        this.runContext = runContext;
        this.fileWriter = fileWriter;
        this.realtime = config != null && config.importRealtime();
    }

    public static DoqaSession getOrInit() {
        DoqaSession local = instance;
        if (local != null) {
            return local;
        }
        synchronized (DoqaSession.class) {
            if (instance == null) {
                instance = create();
            }
            return instance;
        }
    }

    /** The active session's config, or null before initialization (defaults apply). */
    public static DoqaConfig currentConfig() {
        DoqaSession local = instance;
        return local == null ? null : local.config;
    }

    /**
     * True only when the resolved config is a mode-0 API run: the one case where the discovery
     * filter must resolve the selective list. Lets the filter avoid forcing session init (which,
     * in mode 2, would create a run on the server just from IDE test discovery).
     */
    public static boolean discoverySelectionActive() {
        if (instance != null) {
            return instance.enabled && instance.runContext != null
                    && instance.runContext.selectedExternalIds() != null;
        }
        Boolean cached = discoveryVerdict;
        if (cached == null) {
            cached = computeDiscoveryVerdict();
            discoveryVerdict = cached;
        }
        return cached;
    }

    private static boolean computeDiscoveryVerdict() {
        try {
            DoqaConfig cfg = configOverride != null ? configOverride : resolveConfig();
            return cfg.adapterMode() == DoqaConfig.MODE_SELECTIVE
                    && DoqaConfig.REPORTING_API.equals(cfg.effectiveReporting());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static DoqaConfig resolveConfig() {
        Map<String, String> env = envOverride != null ? envOverride : System.getenv();
        return ConfigResolver.resolve(System.getProperties(), env, null);
    }

    private static DoqaSession create() {
        DoqaConfig config = configOverride != null ? configOverride : resolveConfig();
        String sink = config.effectiveReporting();
        if (DoqaConfig.REPORTING_OFF.equals(sink)) {
            LOG.log(Level.FINE, "DoQA: reporting=off, reporter disabled.");
            return new DoqaSession(false, config, null, null, null);
        }
        if (DoqaConfig.REPORTING_FILES.equals(sink)) {
            warnIfUnconfigured(config);
            try {
                AllureFileWriter writer = new AllureFileWriter(
                        Paths.get(config.resultsDir()), AdapterRuntime.frameworkLabel());
                writer.writeEnvironment(config.environment());
                return new DoqaSession(true, config, null, null, writer);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "DoQA: cannot open results dir, disabling: " + e.getMessage(), e);
                return new DoqaSession(false, config, null, null, null);
            }
        }
        // api sink (explicit or via auto)
        if (!config.enabled()) {
            LOG.log(Level.WARNING, "DoQA: reporting=api, but the configuration is incomplete (missing "
                    + String.join(", ", config.missingApiSettings()) + ") - reporting is disabled. "
                    + CONFIG_HINT);
            return new DoqaSession(false, config, null, null, null);
        }
        try {
            ApiClient client = clientFactory != null ? clientFactory.apply(config) : new ApiClient(config);
            RunContext runContext = RunContext.establish(client, config);
            installShutdownHook();
            return new DoqaSession(true, config, client, runContext, null);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA: could not establish run, disabling: " + e.getMessage(), e);
            return new DoqaSession(false, config, null, null, null);
        }
    }

    /**
     * With the default {@code reporting=auto}, missing url/token/spaceId degrades to the file sink:
     * results never reach DoQA, so the fallback is reported instead of happening silently. An
     * explicit {@code reporting=files} is a deliberate choice and stays quiet.
     */
    private static void warnIfUnconfigured(DoqaConfig config) {
        if (!DoqaConfig.REPORTING_AUTO.equals(rawReporting(config))) {
            LOG.log(Level.FINE, "DoQA: reporting=files, results are written to " + config.resultsDir());
            return;
        }
        LOG.log(Level.WARNING, "DoQA: no reporting configuration found (missing "
                + String.join(", ", config.missingApiSettings())
                + ") - results are written as Allure files to '" + config.resultsDir()
                + "' and are NOT sent to DoQA. " + CONFIG_HINT
                + " Set doqa.reporting=files to make the file output explicit and silence this warning.");
    }

    /** Raw {@code reporting} value, normalized; unknown values behave as {@code auto}. */
    private static String rawReporting(DoqaConfig config) {
        String raw = config.reporting();
        if (raw == null) {
            return DoqaConfig.REPORTING_AUTO;
        }
        raw = raw.trim().toLowerCase();
        return DoqaConfig.REPORTING_API.equals(raw)
                || DoqaConfig.REPORTING_FILES.equals(raw)
                || DoqaConfig.REPORTING_OFF.equals(raw)
                ? raw
                : DoqaConfig.REPORTING_AUTO;
    }

    /**
     * Best-effort safety net: a test calling {@code System.exit}, or CI killing the JVM between
     * the last test and plan end, must not silently lose the whole buffered batch. Registered
     * once per JVM; reads the live instance at shutdown, no-op after a normal flush.
     */
    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                DoqaSession local = instance;
                if (local != null) {
                    try {
                        local.flush();
                    } catch (Throwable t) {
                        LOG.log(Level.WARNING, "DoQA: shutdown flush failed", t);
                    }
                }
            }, "doqa-shutdown-flush"));
        } catch (IllegalStateException ignored) {
            // JVM already shutting down
        }
    }

    public boolean fileSink() {
        return fileWriter != null;
    }

    /** Gate for {@link ResultBuilder}: mode-0 selection scope (always true for other sinks). */
    public boolean allowsId(String externalId) {
        return !enabled || runContext == null || runContext.allows(externalId);
    }

    /**
     * Report one finished test. Files sink writes immediately; api sink buffers - per test class
     * in realtime mode (streamed once the class container finishes) or for the whole plan in
     * batch mode.
     *
     * <p>Class fixtures: {@code @BeforeAll} already ran, so the files sink attaches it here; the
     * api paths merge both {@code @BeforeAll} and {@code @AfterAll} at their flush point
     * ({@link #flushClass}/{@link #flush}), when {@code @AfterAll} has also run.
     */
    public void report(ResultBuilder.Built built) {
        if (!enabled || built == null) {
            return;
        }
        warnOnDuplicateId(built);
        if (fileSink()) {
            // @BeforeAll goes into this result's setup; @AfterAll arrives later via the shared
            // class container written at flush (the parser merges both onto each child).
            built.result.prependSetupResults(
                    ClassFixtures.beforeResults(built.classKey, uploader()));
            String uuid = fileWriter.write(built.def, built.result, built.fullName, built.allureId);
            if (built.classKey != null) {
                synchronized (this) {
                    fileUuidsByClass.computeIfAbsent(built.classKey, k -> new ArrayList<>()).add(uuid);
                }
            }
            return;
        }
        if (!runContext.allows(built.def.externalId())) {
            return;
        }
        Reported reported = new Reported(built.def, built.result, built.classKey);
        synchronized (this) {
            if (realtime) {
                realtimeByClass.computeIfAbsent(topLevelKey(built.classKey), k -> new ArrayList<>())
                        .add(reported);
            } else {
                buffer.add(reported);
            }
        }
    }

    /**
     * Realtime streaming point: called when a top-level class container (incl. its
     * {@code @AfterAll}) has finished - uploads that class's results with complete fixtures.
     */
    public void flushClass(String classFqcn) {
        if (!enabled || !realtime || fileSink()) {
            return;
        }
        List<Reported> batch;
        synchronized (this) {
            batch = realtimeByClass.remove(topLevelKey(classFqcn));
        }
        if (batch != null && !batch.isEmpty()) {
            upload(batch);
        }
    }

    /** Flush at plan end: batch upload / realtime leftovers / files-sink shared containers. */
    public void flush() {
        if (!enabled) {
            return;
        }
        if (fileSink()) {
            synchronized (this) {
                for (Map.Entry<String, List<String>> e : fileUuidsByClass.entrySet()) {
                    // shared container carries only @AfterAll (@BeforeAll went into each result)
                    fileWriter.writeContainer(e.getValue(), List.of(),
                            ClassFixtures.afterResults(e.getKey(), uploader()));
                }
                fileUuidsByClass.clear();
                fileWriter.clearAttachmentMeta();
            }
            return;
        }
        List<Reported> snapshot = new ArrayList<>();
        synchronized (this) {
            snapshot.addAll(buffer);
            buffer.clear();
            // realtime: classes whose container event never fired (defensive leftovers)
            for (List<Reported> classReports : realtimeByClass.values()) {
                snapshot.addAll(classReports);
            }
            realtimeByClass.clear();
        }
        if (!snapshot.isEmpty()) {
            upload(snapshot);
        }
    }

    /**
     * Merge class fixtures, then upsert + upload in {@code batchSize} chunks. The snapshot is
     * already detached from the buffers, so a failed chunk loses only itself (logged) and a
     * repeated flush can never double-merge fixtures or re-send old results.
     */
    private void upload(List<Reported> snapshot) {
        for (Reported r : snapshot) {
            r.result.prependSetupResults(ClassFixtures.beforeResults(r.classKey, uploader()));
            r.result.teardownResults(ClassFixtures.afterResults(r.classKey, uploader()));
        }
        // one def per externalId: parameterized invocations collapse to the same def - sending
        // a copy per invocation only bloats the payload (the server keeps one anyway)
        Map<String, AutotestDef> defs = new LinkedHashMap<>();
        for (Reported r : snapshot) {
            defs.putIfAbsent(r.def.externalId(), r.def);
        }
        int chunkSize = config.batchSize();
        List<AutotestDef> defList = new ArrayList<>(defs.values());
        for (int i = 0; i < defList.size(); i += chunkSize) {
            List<AutotestDef> chunk = defList.subList(i, Math.min(i + chunkSize, defList.size()));
            try {
                client.upsertAutotests(chunk);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "DoQA: upsert chunk failed (" + chunk.size()
                        + " defs dropped): " + e.getMessage());
            }
        }
        for (int i = 0; i < snapshot.size(); i += chunkSize) {
            List<Reported> chunk = snapshot.subList(i, Math.min(i + chunkSize, snapshot.size()));
            List<AutotestResult> results = new ArrayList<>(chunk.size());
            for (Reported r : chunk) {
                results.add(r.result);
            }
            try {
                client.uploadResults(runContext.runId(), runContext.configurationId(), results);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "DoQA: results chunk failed (" + results.size()
                        + " results lost): " + e.getMessage());
            }
        }
    }

    /** The active sink's attachment uploader (multipart upload or results-dir copy). */
    public ResultBuilder.AttachmentUploader uploader() {
        return this::uploadAttachment;
    }

    private String uploadAttachment(AttachmentRef ref) {
        if (!enabled || ref == null) {
            return null;
        }
        try {
            if (fileSink()) {
                return ref.path != null
                        ? fileWriter.storeAttachment(ref.path)
                        : fileWriter.storeAttachment(ref.name, ref.content, ref.contentType);
            }
            return ref.path != null
                    ? client.uploadAttachment(ref.path)
                    : client.uploadAttachment(ref.name, ref.content, ref.contentType);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "DoQA: attachment handling failed for " + ref, e);
            return null;
        }
    }

    /**
     * Two different methods reporting under one externalId merge into a single autotest as
     * "attempts" - almost always a class-level {@code @DoqaId} on a multi-test class or a
     * copy-pasted id. Parameterized invocations of ONE method share an id by design and stay
     * silent.
     */
    private void warnOnDuplicateId(ResultBuilder.Built built) {
        String id = built.def.externalId();
        String methodKey = built.methodKey;
        if (id == null || methodKey == null) {
            return;
        }
        String owner = idOwners.putIfAbsent(id, methodKey);
        if (owner != null && !owner.equals(methodKey) && duplicateIdsWarned.add(id)) {
            LOG.warning("DoQA: externalId \"" + id + "\" is reported by both " + owner + " and "
                    + methodKey + " - their results merge into one autotest (check class-level"
                    + " @DoqaId / duplicated ids)");
        }
    }

    private static String topLevelKey(String classKey) {
        if (classKey == null) {
            return "";
        }
        int nested = classKey.indexOf('$');
        return nested < 0 ? classKey : classKey.substring(0, nested);
    }

    // ---- test seams -----------------------------------------------------
    public static void setClientFactory(Function<DoqaConfig, ApiClient> factory) {
        clientFactory = factory;
    }

    public static void setConfigOverride(DoqaConfig config) {
        configOverride = config;
        discoveryVerdict = null;
    }

    /** Replaces {@code System.getenv()} for config resolution (isolates tests from machine env). */
    public static void setEnvOverride(Map<String, String> env) {
        envOverride = env;
        discoveryVerdict = null;
    }

    public static void reset() {
        instance = null;
        discoveryVerdict = null;
        ClassFixtures.reset();
    }
}
