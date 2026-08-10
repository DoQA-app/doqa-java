package app.doqa.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolved DoQA client-core configuration. See {@link ConfigResolver} for the full catalog of
 * keys, aliases and environment variables - that javadoc is the single source of truth for
 * configuration names; this class only documents semantics.
 *
 * <p>Run-selection semantics ({@code adapterMode}): {@link #MODE_NEW_RUN} (default) creates a
 * fresh test run; {@link #MODE_EXISTING_RUN} reports everything into the configured
 * {@code testRunId}; {@link #MODE_SELECTIVE} reports into {@code testRunId} restricted to the
 * run's selective autotest list. When {@code testRunId} is configured but {@code adapterMode} is
 * not, {@link RunContext#establish} treats it as {@link #MODE_EXISTING_RUN} - an explicitly
 * targeted run must never be silently ignored.
 */
public final class DoqaConfig {

    /** Existing run + the run's SELECTIVE autotest list ({@code adapterMode=0|selective}). */
    public static final int MODE_SELECTIVE = 0;
    /** Existing run, all results ({@code adapterMode=1|existing}). */
    public static final int MODE_EXISTING_RUN = 1;
    /** Create a fresh run, all results ({@code adapterMode=2|new}). */
    public static final int MODE_NEW_RUN = 2;
    public static final int DEFAULT_ADAPTER_MODE = MODE_NEW_RUN;

    /** Reporting sink: Direct API when configured, Allure-files emit otherwise. */
    public static final String REPORTING_AUTO = "auto";
    /** Reporting sink: Direct Autotest API only (requires url/token/spaceId). */
    public static final String REPORTING_API = "api";
    /** Reporting sink: Allure-results file emit only (no network, no credentials). */
    public static final String REPORTING_FILES = "files";
    /** Reporting disabled entirely. */
    public static final String REPORTING_OFF = "off";
    public static final String DEFAULT_RESULTS_DIR = "results";

    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000L;
    public static final int DEFAULT_RETRIES = 3;
    public static final long DEFAULT_RETRY_BACKOFF_MS = 500L;
    public static final int DEFAULT_MAX_TRACE_LENGTH = 100_000;
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 10_000;
    public static final int DEFAULT_MAX_PARAMETER_LENGTH = 2_000;

    private final String url;
    private final String token;
    private final String spaceId;
    private final String configurationId;
    private final String testRunId;
    private final String testRunName;
    private final int adapterMode;
    /** True when the user set adapterMode explicitly (vs. the built-in default). */
    private final boolean adapterModeExplicit;
    private final boolean importRealtime;
    private final boolean certValidation;
    private final String proxy;
    private final String reporting;
    private final String resultsDir;
    /** Environment label for the run (DoQA environment matrix). */
    private final String environment;
    // CI pipeline linkage (echo of CI_PIPELINE_ID / DOQA_PIPELINE_ID): sent in POST /test-runs
    // as pipeline_id - otherwise DoQA cannot show the pipeline/jobs for a run started
    // directly from CI.
    private final String pipelineId;
    // Echo of DOQA_CI_RUN_ID (present when the pipeline was started by DoQA): sent with results
    // so the backend links them to the exact pipeline (quality gate / sources / multi-pipeline runs).
    private final String ciRunId;
    private final String branch;
    private final int batchSize;
    private final long requestTimeoutMs;
    private final int retries;
    private final long retryBackoffMs;
    private final int maxTraceLength;
    private final int maxMessageLength;
    private final int maxParameterLength;

    private DoqaConfig(Builder b) {
        // Blank collapses to null: an explicitly EMPTY value (the "clear the inherited setting"
        // form) must behave exactly like an absent one - never travel to the wire as "".
        this.url = blankToNull(b.url);
        this.token = blankToNull(b.token);
        this.spaceId = blankToNull(b.spaceId);
        this.configurationId = blankToNull(b.configurationId);
        this.testRunId = blankToNull(b.testRunId);
        this.testRunName = blankToNull(b.testRunName);
        this.adapterMode = b.adapterMode;
        this.adapterModeExplicit = b.adapterModeExplicit;
        this.importRealtime = b.importRealtime;
        this.certValidation = b.certValidation;
        this.proxy = blankToNull(b.proxy);
        this.reporting = b.reporting;
        this.resultsDir = b.resultsDir;
        this.environment = blankToNull(b.environment);
        this.pipelineId = blankToNull(b.pipelineId);
        this.ciRunId = blankToNull(b.ciRunId);
        this.branch = blankToNull(b.branch);
        this.batchSize = b.batchSize;
        this.requestTimeoutMs = b.requestTimeoutMs;
        this.retries = b.retries;
        this.retryBackoffMs = b.retryBackoffMs;
        this.maxTraceLength = b.maxTraceLength;
        this.maxMessageLength = b.maxMessageLength;
        this.maxParameterLength = b.maxParameterLength;
    }

    /** Resolve from file ({@code doqa.properties}) &lt; env ({@code DOQA_*}) &lt; system-props (CLI). */
    public static DoqaConfig resolve() {
        return ConfigResolver.resolve();
    }

    public String url() { return url; }
    public String token() { return token; }
    public String spaceId() { return spaceId; }
    public String configurationId() { return configurationId; }
    public String testRunId() { return testRunId; }
    public String testRunName() { return testRunName; }
    public int adapterMode() { return adapterMode; }
    public boolean adapterModeExplicit() { return adapterModeExplicit; }
    public boolean importRealtime() { return importRealtime; }
    public boolean certValidation() { return certValidation; }
    public String proxy() { return proxy; }
    public String resultsDir() { return resultsDir; }
    public String environment() { return environment; }
    public String pipelineId() { return pipelineId; }
    public String ciRunId() { return ciRunId; }
    public String branch() { return branch; }
    /** Max results per batched upload POST. */
    public int batchSize() { return batchSize; }
    public long requestTimeoutMs() { return requestTimeoutMs; }
    public int retries() { return retries; }
    public long retryBackoffMs() { return retryBackoffMs; }
    /** Truncation limit (chars) for stack traces in results. */
    public int maxTraceLength() { return maxTraceLength; }
    /** Truncation limit (chars) for result/step messages. */
    public int maxMessageLength() { return maxMessageLength; }
    /** Truncation limit (chars) for stringified parameter values. */
    public int maxParameterLength() { return maxParameterLength; }

    /** Raw reporting value: {@code auto} (default) | {@code api} | {@code files} | {@code off}. */
    public String reporting() { return reporting; }

    /**
     * The effective reporting sink: {@code auto} resolves to {@code api} when the API config is
     * complete and to {@code files} otherwise; unknown values fall back to {@code auto} rules.
     */
    public String effectiveReporting() {
        String r = reporting == null ? REPORTING_AUTO : reporting.trim().toLowerCase();
        if (REPORTING_API.equals(r) || REPORTING_FILES.equals(r) || REPORTING_OFF.equals(r)) {
            return r;
        }
        return enabled() ? REPORTING_API : REPORTING_FILES;
    }

    /** True when we have the minimum to talk to the API (url + token + space). */
    public boolean enabled() {
        return notBlank(url) && notBlank(token) && notBlank(spaceId);
    }

    /**
     * Canonical names of the missing API settings, in configuration order - empty when
     * {@link #enabled()}. Adapters name them when reporting an unconfigured run.
     */
    public List<String> missingApiSettings() {
        List<String> missing = new ArrayList<>(3);
        if (!notBlank(url)) {
            missing.add("url");
        }
        if (!notBlank(token)) {
            missing.add("token");
        }
        if (!notBlank(spaceId)) {
            missing.add("spaceId");
        }
        return missing;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    /** Mutable builder - used by {@link ConfigResolver} and by adapters injecting explicit values. */
    public static final class Builder {
        private String url;
        private String token;
        private String spaceId;
        private String configurationId;
        private String testRunId;
        private String testRunName;
        private int adapterMode = DEFAULT_ADAPTER_MODE;
        private boolean adapterModeExplicit;
        private boolean importRealtime = false;
        private boolean certValidation = true;
        private String proxy;
        private String reporting = REPORTING_AUTO;
        private String resultsDir = DEFAULT_RESULTS_DIR;
        private String environment;
        private String pipelineId;
        private String ciRunId;
        private String branch;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
        private int retries = DEFAULT_RETRIES;
        private long retryBackoffMs = DEFAULT_RETRY_BACKOFF_MS;
        private int maxTraceLength = DEFAULT_MAX_TRACE_LENGTH;
        private int maxMessageLength = DEFAULT_MAX_MESSAGE_LENGTH;
        private int maxParameterLength = DEFAULT_MAX_PARAMETER_LENGTH;

        public Builder url(String v) { if (v != null) this.url = v; return this; }
        public Builder token(String v) { if (v != null) this.token = v; return this; }
        public Builder spaceId(String v) { if (v != null) this.spaceId = v; return this; }
        public Builder configurationId(String v) { if (v != null) this.configurationId = v; return this; }
        public Builder testRunId(String v) { if (v != null) this.testRunId = v; return this; }
        public Builder testRunName(String v) { if (v != null) this.testRunName = v; return this; }
        public Builder adapterMode(int v) {
            this.adapterMode = v;
            this.adapterModeExplicit = true;
            return this;
        }
        public Builder importRealtime(boolean v) { this.importRealtime = v; return this; }
        public Builder certValidation(boolean v) { this.certValidation = v; return this; }
        public Builder proxy(String v) { if (v != null) this.proxy = v; return this; }
        public Builder reporting(String v) { if (v != null) this.reporting = v; return this; }
        public Builder resultsDir(String v) { if (v != null) this.resultsDir = v; return this; }
        public Builder environment(String v) { if (v != null) this.environment = v; return this; }

        public Builder pipelineId(String v) { if (v != null) this.pipelineId = v; return this; }
        public Builder ciRunId(String v) { if (v != null) this.ciRunId = v; return this; }
        public Builder branch(String v) { if (v != null) this.branch = v; return this; }
        public Builder batchSize(int v) { if (v > 0) this.batchSize = v; return this; }
        public Builder requestTimeoutMs(long v) { if (v > 0) this.requestTimeoutMs = v; return this; }
        public Builder retries(int v) { if (v > 0) this.retries = v; return this; }
        public Builder retryBackoffMs(long v) { if (v >= 0) this.retryBackoffMs = v; return this; }
        public Builder maxTraceLength(int v) { if (v > 0) this.maxTraceLength = v; return this; }
        public Builder maxMessageLength(int v) { if (v > 0) this.maxMessageLength = v; return this; }
        public Builder maxParameterLength(int v) { if (v > 0) this.maxParameterLength = v; return this; }

        public DoqaConfig build() {
            return new DoqaConfig(this);
        }
    }
}
