package app.doqa.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Resolved run to report into, honouring {@code adapterMode}.
 *
 * <ul>
 *   <li><b>{@link DoqaConfig#MODE_NEW_RUN}</b> - create a fresh test run; report ALL results.</li>
 *   <li><b>{@link DoqaConfig#MODE_SELECTIVE}</b> - existing {@code testRunId} + its SELECTIVE
 *       autotest list; only those external ids are in scope (adapter deselects the rest).</li>
 *   <li><b>{@link DoqaConfig#MODE_EXISTING_RUN}</b> - existing {@code testRunId}; report ALL
 *       results (no selection).</li>
 * </ul>
 */
public final class RunContext {

    private static final Logger LOG = Logger.getLogger(RunContext.class.getName());

    private final String runId;
    private final String configurationId;
    private final int mode;
    /** null =&gt; everything allowed; a set =&gt; only these external ids are in scope (mode 0). */
    private final Set<String> selectedExternalIds;
    /**
     * Raw SERVER order of the selective list (mode 0), or null when there is no plan.
     * {@link #selectedExternalIds} stays authoritative for membership ({@link #allows});
     * this preserves the execution-order contract for order-capable consumers (plan orderers).
     */
    private final List<String> selectedOrder;
    /** externalId -&gt; 0-based plan position (first occurrence wins); empty when no plan. */
    private final Map<String, Integer> orderIndex;
    /**
     * {@code <namespace>.<classname>#<method>} of every selected autotest ({@code <classname>#<method>}
     * when the server has no namespace for it); null when any of them has no identity at all.
     */
    private final Set<String> selectedLocations;

    public RunContext(String runId, String configurationId, int mode, Set<String> selectedExternalIds) {
        this(runId, configurationId, mode, selectedExternalIds, null, null);
    }

    /** Mode-0 run: membership, execution order and identities all come from one plan. */
    public static RunContext selective(String runId, String configurationId,
                                       List<PlannedAutotest> plan) {
        List<String> selected = new ArrayList<>();
        for (PlannedAutotest entry : plan) {
            selected.add(entry.externalId());
        }
        return new RunContext(runId, configurationId, DoqaConfig.MODE_SELECTIVE,
                new HashSet<>(selected), selected, plan);
    }

    private RunContext(String runId, String configurationId, int mode, Set<String> selectedExternalIds,
                       List<String> selectedOrder, List<PlannedAutotest> plan) {
        this.runId = runId;
        this.configurationId = configurationId;
        this.mode = mode;
        this.selectedExternalIds = selectedExternalIds;
        this.selectedOrder = selectedOrder == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(selectedOrder));
        Map<String, Integer> index = new HashMap<>();
        if (this.selectedOrder != null) {
            for (int i = 0; i < this.selectedOrder.size(); i++) {
                index.putIfAbsent(this.selectedOrder.get(i), i);
            }
        }
        this.orderIndex = index;
        this.selectedLocations = locationsOf(plan);
    }

    public String runId() { return runId; }
    public String configurationId() { return configurationId; }
    public int mode() { return mode; }
    public Set<String> selectedExternalIds() { return selectedExternalIds; }

    /** Server-ordered selective plan (mode 0) or null when no plan. */
    public List<String> selectedOrder() { return selectedOrder; }

    public boolean allows(String externalId) {
        return selectedExternalIds == null || selectedExternalIds.contains(externalId);
    }

    /**
     * Whether a container whose tests are identified only while they execute may hold a selected
     * autotest, matched by the identity they were last reported under. Unknown identity - in the
     * plan or in the container - returns {@code true}.
     */
    public boolean mayHoldSelected(String namespace, String classname, String method) {
        if (selectedLocations == null) {
            return true;
        }
        String cls = trimmed(classname);
        String m = trimmed(method);
        if (cls == null || m == null) {
            return true;
        }
        String ns = trimmed(namespace);
        return selectedLocations.contains(location(ns, cls, m))
                || selectedLocations.contains(location(null, cls, m));
    }

    /**
     * Position of {@code externalId} in the server-ordered plan. Not in the plan (or no
     * plan) =&gt; {@link Integer#MAX_VALUE} - stable sorts keep such items at the tail in their
     * original relative order (order never changes the SET of tests, only the sequence).
     */
    public int orderIndex(String externalId) {
        Integer index = orderIndex.get(externalId);
        return index != null ? index : Integer.MAX_VALUE;
    }

    /** Establish the run for {@code config.adapterMode()}. */
    public static RunContext establish(ApiClient client, DoqaConfig config) {
        int mode = effectiveMode(config);
        String confId = config.configurationId();

        if (mode == DoqaConfig.MODE_NEW_RUN) {
            // A per-process external_key makes the create idempotent server-side, so the client
            // may safely retry it - a lost response must not leave two runs behind.
            String runId = client.createTestRun(config.testRunName(), confId,
                    "doqa-client-" + UUID.randomUUID());
            return new RunContext(requireCreatedRunId(runId), confId, DoqaConfig.MODE_NEW_RUN, null);
        }
        if (mode == DoqaConfig.MODE_SELECTIVE) {
            // modes 0/1 report into a pre-existing run - a missing testRunId means every
            // upload would 4xx; fail fast here so the session disables cleanly instead.
            String runId = requireRunId(config, DoqaConfig.MODE_SELECTIVE);
            return selective(runId, confId, client.getRunAutotests(runId, confId));
        }
        return new RunContext(requireRunId(config, DoqaConfig.MODE_EXISTING_RUN), confId,
                DoqaConfig.MODE_EXISTING_RUN, null);
    }

    /**
     * The mode to run under. A configured {@code testRunId} with no explicit {@code adapterMode}
     * means "report into that run" ({@link DoqaConfig#MODE_EXISTING_RUN}) - otherwise the default
     * mode would silently ignore the id and send results into a freshly created run.
     */
    private static int effectiveMode(DoqaConfig config) {
        boolean hasRunId = config.testRunId() != null && !config.testRunId().trim().isEmpty();
        if (hasRunId && !config.adapterModeExplicit()) {
            return DoqaConfig.MODE_EXISTING_RUN;
        }
        if (hasRunId && config.adapterMode() == DoqaConfig.MODE_NEW_RUN) {
            LOG.warning("DoQA: adapterMode=2 creates a NEW run - the configured testRunId "
                    + config.testRunId() + " is ignored (use adapterMode=1 to report into it)");
        }
        return config.adapterMode();
    }

    private static String requireCreatedRunId(String runId) {
        if (runId == null || runId.trim().isEmpty()) {
            // 2xx without a runId (contract drift, a proxy swallowing the body): fail fast so
            // the session disables with a clear warning instead of uploading without test_run_id.
            throw new ApiError("test-run create returned no runId - check the DoQA url/response");
        }
        return runId;
    }

    /** Location keys of a plan, or null when any entry lacks one. An empty plan places nothing. */
    private static Set<String> locationsOf(List<PlannedAutotest> plan) {
        if (plan == null) {
            return null;
        }
        Set<String> locations = new HashSet<>();
        for (PlannedAutotest entry : plan) {
            String classname = trimmed(entry.classname());
            String method = trimmed(entry.runnerMethod());
            if (classname == null || method == null) {
                return null;
            }
            locations.add(location(trimmed(entry.namespace()), classname, method));
        }
        return locations;
    }

    private static String location(String namespace, String classname, String method) {
        return (namespace == null ? "" : namespace + ".") + classname + "#" + method;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }

    private static String requireRunId(DoqaConfig config, int mode) {
        String runId = config.testRunId();
        if (runId == null || runId.trim().isEmpty()) {
            throw new ApiError("adapterMode " + mode + " requires testRunId, but none was configured");
        }
        return runId;
    }
}
