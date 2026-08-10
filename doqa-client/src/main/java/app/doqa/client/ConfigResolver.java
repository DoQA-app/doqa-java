package app.doqa.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Config-resolver - merge file / env / system-props with priority
 * <b>system-props (CLI) &gt; env &gt; file</b>.
 *
 * <p>Sources:
 * <ul>
 *   <li><b>file</b> - {@code doqa.properties} (UTF-8) in the working directory, or the path given
 *       by the {@code doqa.config} system property / {@code DOQA_CONFIG} env. Keys are canonical
 *       field names or compatibility aliases (e.g. {@code privateToken}, {@code projectId}).
 *       An explicitly configured path that cannot be read is reported with a WARNING (a broken
 *       config must not silently degrade to defaults).</li>
 *   <li><b>env</b> - {@code DOQA_<FIELD>} (snake case, e.g. {@code DOQA_TEST_RUN_ID},
 *       {@code DOQA_ENVIRONMENT}, {@code DOQA_BATCH_SIZE}) plus aliases
 *       {@code DOQA_PRIVATE_TOKEN} / {@code DOQA_PROJECT_ID}; CI correlation auto-picked from
 *       {@code CI_PIPELINE_ID} / {@code GITHUB_RUN_ID} / {@code CI_COMMIT_REF_NAME} /
 *       {@code GITHUB_REF_NAME}. Empty env values are ignored (CI systems export empty
 *       variables for unset settings).</li>
 *   <li><b>system-props</b> (CLI, highest) - {@code -Ddoqa.<field>=...}. An explicitly EMPTY
 *       CLI value clears the field inherited from lower layers
 *       (e.g. {@code -Ddoqa.pipelineId=} drops an auto-picked {@code CI_PIPELINE_ID}).</li>
 * </ul>
 *
 * <p>Canonical fields: {@code url}, {@code token}, {@code spaceId}, {@code configurationId},
 * {@code testRunId}, {@code testRunName}, {@code adapterMode} ({@code 0|1|2} or
 * {@code selective|existing|new}), {@code importRealtime}, {@code certValidation}, {@code proxy},
 * {@code reporting} ({@code auto|api|files|off}), {@code resultsDir}, {@code environment},
 * {@code pipelineId}, {@code ciRunId}, {@code branch}, {@code batchSize},
 * {@code requestTimeoutMs}, {@code retries}, {@code retryBackoffMs}, {@code maxTraceLength},
 * {@code maxMessageLength}, {@code maxParameterLength}.
 */
public final class ConfigResolver {

    private static final Logger LOG = Logger.getLogger(ConfigResolver.class.getName());

    /**
     * A value that is nothing but a variable reference ({@code $DOQA_TOKEN},
     * {@code ${DOQA_TOKEN}}) is an unexpanded CI placeholder rather than a setting - the literal
     * text reaches the process whenever the referenced variable does not exist.
     */
    private static final Pattern UNEXPANDED_REF =
            Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?");

    private ConfigResolver() {
    }

    // canonical field keys
    static final String URL = "url";
    static final String TOKEN = "token";
    static final String SPACE_ID = "spaceId";
    static final String CONFIGURATION_ID = "configurationId";
    static final String TEST_RUN_ID = "testRunId";
    static final String TEST_RUN_NAME = "testRunName";
    static final String ADAPTER_MODE = "adapterMode";
    static final String IMPORT_REALTIME = "importRealtime";
    static final String CERT_VALIDATION = "certValidation";
    static final String PROXY = "proxy";
    static final String REPORTING = "reporting";
    static final String RESULTS_DIR = "resultsDir";
    static final String ENVIRONMENT = "environment";
    static final String PIPELINE_ID = "pipelineId";
    static final String CI_RUN_ID = "ciRunId";
    static final String BRANCH = "branch";
    static final String BATCH_SIZE = "batchSize";
    static final String REQUEST_TIMEOUT_MS = "requestTimeoutMs";
    static final String RETRIES = "retries";
    static final String RETRY_BACKOFF_MS = "retryBackoffMs";
    static final String MAX_TRACE_LENGTH = "maxTraceLength";
    static final String MAX_MESSAGE_LENGTH = "maxMessageLength";
    static final String MAX_PARAMETER_LENGTH = "maxParameterLength";

    public static DoqaConfig resolve() {
        return resolve(System.getProperties(), System.getenv(), null);
    }

    /** Testable overload: explicit layers (any may be null). */
    public static DoqaConfig resolve(Properties sysProps, Map<String, String> env, Path fileOverride) {
        Map<String, String> file = normalize(loadFile(sysProps, env, fileOverride), false);
        Map<String, String> envLayer = normalize(collectEnv(env), false);
        // CLI keeps empty values: an explicit -Ddoqa.<field>= clears the inherited setting.
        Map<String, String> cli = normalize(collectSysProps(sysProps), true);

        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(file);
        merged.putAll(envLayer); // env overrides file
        merged.putAll(cli);      // cli overrides env

        DoqaConfig.Builder b = new DoqaConfig.Builder()
                .url(merged.get(URL))
                .token(merged.get(TOKEN))
                .spaceId(merged.get(SPACE_ID))
                .configurationId(merged.get(CONFIGURATION_ID))
                .testRunId(merged.get(TEST_RUN_ID))
                .testRunName(merged.get(TEST_RUN_NAME))
                .proxy(merged.get(PROXY))
                .reporting(merged.get(REPORTING))
                .environment(merged.get(ENVIRONMENT))
                .pipelineId(merged.get(PIPELINE_ID))
                .ciRunId(merged.get(CI_RUN_ID))
                .branch(merged.get(BRANCH));
        if (notBlank(merged.get(RESULTS_DIR))) {
            b.resultsDir(merged.get(RESULTS_DIR));
        }
        if (notBlank(merged.get(ADAPTER_MODE))) {
            b.adapterMode(parseMode(merged.get(ADAPTER_MODE)));
        }
        if (merged.containsKey(IMPORT_REALTIME)) {
            b.importRealtime(parseBool(merged.get(IMPORT_REALTIME)));
        }
        if (merged.containsKey(CERT_VALIDATION)) {
            b.certValidation(parseBool(merged.get(CERT_VALIDATION)));
        }
        b.batchSize(parseInt(merged.get(BATCH_SIZE), DoqaConfig.DEFAULT_BATCH_SIZE));
        b.requestTimeoutMs(parseLong(merged.get(REQUEST_TIMEOUT_MS), DoqaConfig.DEFAULT_REQUEST_TIMEOUT_MS));
        b.retries(parseInt(merged.get(RETRIES), DoqaConfig.DEFAULT_RETRIES));
        b.retryBackoffMs(parseLong(merged.get(RETRY_BACKOFF_MS), DoqaConfig.DEFAULT_RETRY_BACKOFF_MS));
        b.maxTraceLength(parseInt(merged.get(MAX_TRACE_LENGTH), DoqaConfig.DEFAULT_MAX_TRACE_LENGTH));
        b.maxMessageLength(parseInt(merged.get(MAX_MESSAGE_LENGTH), DoqaConfig.DEFAULT_MAX_MESSAGE_LENGTH));
        b.maxParameterLength(parseInt(merged.get(MAX_PARAMETER_LENGTH), DoqaConfig.DEFAULT_MAX_PARAMETER_LENGTH));
        return b.build();
    }

    // ---- layers ---------------------------------------------------------
    private static Map<String, String> loadFile(Properties sysProps, Map<String, String> env, Path override) {
        Path path = override;
        boolean explicit = override != null;
        if (path == null) {
            String fromProp = sysProps == null ? null : sysProps.getProperty("doqa.config");
            String fromEnv = env == null ? null : env.get("DOQA_CONFIG");
            String p = fromProp != null ? fromProp : fromEnv;
            explicit = p != null;
            path = p != null ? Paths.get(p) : Paths.get("doqa.properties");
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isReadable(path)) {
            if (explicit) {
                LOG.warning("DoQA config file " + path + " is not readable - ignoring it");
            }
            return out;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            // UTF-8 (not the Properties default ISO-8859-1): non-ASCII values such as a Russian
            // testRunName must survive without unicode escaping.
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warning("DoQA config file " + path + " could not be parsed: " + e.getMessage());
            return out;
        }
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        return out;
    }

    private static Map<String, String> collectEnv(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env == null) {
            return out;
        }
        putEnv(out, env, "DOQA_URL", URL);
        putEnv(out, env, "DOQA_TOKEN", TOKEN);
        putEnv(out, env, "DOQA_PRIVATE_TOKEN", TOKEN);
        putEnv(out, env, "DOQA_SPACE_ID", SPACE_ID);
        putEnv(out, env, "DOQA_PROJECT_ID", SPACE_ID);
        putEnv(out, env, "DOQA_CONFIGURATION_ID", CONFIGURATION_ID);
        putEnv(out, env, "DOQA_TEST_RUN_ID", TEST_RUN_ID);
        putEnv(out, env, "DOQA_TEST_RUN_NAME", TEST_RUN_NAME);
        putEnv(out, env, "DOQA_ADAPTER_MODE", ADAPTER_MODE);
        putEnv(out, env, "DOQA_IMPORT_REALTIME", IMPORT_REALTIME);
        putEnv(out, env, "DOQA_CERT_VALIDATION", CERT_VALIDATION);
        putEnv(out, env, "DOQA_PROXY", PROXY);
        putEnv(out, env, "DOQA_REPORTING", REPORTING);
        putEnv(out, env, "DOQA_RESULTS_DIR", RESULTS_DIR);
        putEnv(out, env, "DOQA_ENVIRONMENT", ENVIRONMENT);
        // CI pipeline linkage: an explicit DOQA_PIPELINE_ID wins (putEnv never overwrites);
        // otherwise auto-picked from the standard CI variables (GitLab / GitHub Actions).
        putEnv(out, env, "DOQA_PIPELINE_ID", PIPELINE_ID);
        putEnv(out, env, "CI_PIPELINE_ID", PIPELINE_ID);
        putEnv(out, env, "GITHUB_RUN_ID", PIPELINE_ID);
        // DoQA-initiated launches pass their ci_run id (DOQA_CI_RUN_ID) - echoed back with
        // results so the backend links them to the exact pipeline (gate / sources / multi-pipeline runs).
        putEnv(out, env, "DOQA_CI_RUN_ID", CI_RUN_ID);
        putEnv(out, env, "DOQA_BRANCH", BRANCH);
        putEnv(out, env, "CI_COMMIT_REF_NAME", BRANCH);
        putEnv(out, env, "GITHUB_REF_NAME", BRANCH);
        putEnv(out, env, "DOQA_BATCH_SIZE", BATCH_SIZE);
        putEnv(out, env, "DOQA_REQUEST_TIMEOUT_MS", REQUEST_TIMEOUT_MS);
        putEnv(out, env, "DOQA_RETRIES", RETRIES);
        putEnv(out, env, "DOQA_RETRY_BACKOFF_MS", RETRY_BACKOFF_MS);
        putEnv(out, env, "DOQA_MAX_TRACE_LENGTH", MAX_TRACE_LENGTH);
        putEnv(out, env, "DOQA_MAX_MESSAGE_LENGTH", MAX_MESSAGE_LENGTH);
        putEnv(out, env, "DOQA_MAX_PARAMETER_LENGTH", MAX_PARAMETER_LENGTH);
        return out;
    }

    private static void putEnv(Map<String, String> out, Map<String, String> env, String key, String field) {
        String v = env.get(key);
        if (v != null && !v.isEmpty() && !out.containsKey(field)) {
            out.put(field, v);
        }
    }

    private static Map<String, String> collectSysProps(Properties sysProps) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sysProps == null) {
            return out;
        }
        for (String name : sysProps.stringPropertyNames()) {
            if (name.startsWith("doqa.")) {
                out.put(name.substring("doqa.".length()), sysProps.getProperty(name));
            }
        }
        return out;
    }

    // ---- normalization --------------------------------------------------
    private static Map<String, String> normalize(Map<String, String> raw, boolean keepEmpty) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String field = canonical(e.getKey());
            String value = e.getValue();
            if (field == null || value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (UNEXPANDED_REF.matcher(trimmed).matches()) {
                LOG.warning("DoQA: " + field + " is set to the unexpanded variable reference \""
                        + trimmed + "\" - treating it as unset. Check that the CI variable exists "
                        + "and is exported to this job.");
                continue;
            }
            if (!trimmed.isEmpty() || keepEmpty) {
                out.put(field, trimmed);
            }
        }
        return out;
    }

    /** Map an arbitrary key (canonical / alias / dashed / snake) to a canonical field name. */
    static String canonical(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim().toLowerCase().replace("-", "").replace("_", "");
        switch (k) {
            case "url": return URL;
            case "token":
            case "privatetoken": return TOKEN;
            case "spaceid":
            case "projectid": return SPACE_ID;
            case "configurationid": return CONFIGURATION_ID;
            case "testrunid": return TEST_RUN_ID;
            case "testrunname": return TEST_RUN_NAME;
            case "adaptermode": return ADAPTER_MODE;
            case "importrealtime": return IMPORT_REALTIME;
            case "certvalidation": return CERT_VALIDATION;
            case "proxy": return PROXY;
            case "reporting": return REPORTING;
            case "resultsdir": return RESULTS_DIR;
            case "environment": return ENVIRONMENT;
            case "pipelineid": return PIPELINE_ID;
            case "cirunid": return CI_RUN_ID;
            case "branch": return BRANCH;
            case "batchsize": return BATCH_SIZE;
            case "requesttimeoutms": return REQUEST_TIMEOUT_MS;
            case "retries": return RETRIES;
            case "retrybackoffms": return RETRY_BACKOFF_MS;
            case "maxtracelength": return MAX_TRACE_LENGTH;
            case "maxmessagelength": return MAX_MESSAGE_LENGTH;
            case "maxparameterlength": return MAX_PARAMETER_LENGTH;
            default: return null;
        }
    }

    /** {@code 0|1|2} or a symbolic name: {@code selective}, {@code existing}, {@code new}. */
    private static int parseMode(String v) {
        String s = v.trim().toLowerCase();
        switch (s) {
            case "selective": return DoqaConfig.MODE_SELECTIVE;
            case "existing":
            case "existingrun":
            case "existing-run": return DoqaConfig.MODE_EXISTING_RUN;
            case "new":
            case "newrun":
            case "new-run": return DoqaConfig.MODE_NEW_RUN;
            default: return parseInt(s, DoqaConfig.DEFAULT_ADAPTER_MODE);
        }
    }

    private static int parseInt(String v, int def) {
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseBool(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("y");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
