package app.doqa.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One autotest result (element of the {@code results} payload). {@code parameters} is a LIST. */
public final class AutotestResult implements Model {

    private String externalId;
    private String outcome;   // wire value
    private String name;
    private Long startedOn;   // epoch-ms
    private Long completedOn;
    private Long durationMs;
    private String message;
    private String traces;
    private final List<Parameter> parameters = new ArrayList<>();
    private final List<StepResult> stepResults = new ArrayList<>();
    private final List<StepResult> setupResults = new ArrayList<>();
    private final List<StepResult> teardownResults = new ArrayList<>();
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private boolean createManualCase;

    public AutotestResult(String externalId, String outcome) {
        this.externalId = externalId;
        this.outcome = outcome;
    }

    public AutotestResult(String externalId, Outcome outcome) {
        this(externalId, outcome == null ? null : outcome.wire());
    }

    public AutotestResult externalId(String v) { this.externalId = v; return this; }
    public AutotestResult outcome(String v) { this.outcome = v; return this; }
    public AutotestResult outcome(Outcome v) { this.outcome = v == null ? null : v.wire(); return this; }
    public AutotestResult name(String v) { this.name = v; return this; }
    public AutotestResult startedOn(Long epochMs) { this.startedOn = epochMs; return this; }
    public AutotestResult completedOn(Long epochMs) { this.completedOn = epochMs; return this; }
    public AutotestResult startedOn(java.time.Instant v) {
        return startedOn(v == null ? null : v.toEpochMilli());
    }
    public AutotestResult completedOn(java.time.Instant v) {
        return completedOn(v == null ? null : v.toEpochMilli());
    }
    public AutotestResult durationMs(Long v) { this.durationMs = v; return this; }
    public AutotestResult message(String v) { this.message = v; return this; }
    public AutotestResult traces(String v) { this.traces = v; return this; }
    public AutotestResult parameters(List<Parameter> v) { if (v != null) parameters.addAll(v); return this; }
    public AutotestResult stepResults(List<StepResult> v) { if (v != null) stepResults.addAll(v); return this; }
    public AutotestResult setupResults(List<StepResult> v) { if (v != null) setupResults.addAll(v); return this; }
    public AutotestResult teardownResults(List<StepResult> v) { if (v != null) teardownResults.addAll(v); return this; }

    /** Prepend setup entries - class-level (@BeforeAll) fixtures go BEFORE the per-test ones. */
    public AutotestResult prependSetupResults(List<StepResult> v) {
        if (v != null && !v.isEmpty()) {
            setupResults.addAll(0, v);
        }
        return this;
    }
    public AutotestResult attachments(List<Attachment> v) { if (v != null) attachments.addAll(v); return this; }
    public AutotestResult links(List<Link> v) { if (v != null) links.addAll(v); return this; }
    public AutotestResult createManualCase(boolean v) { this.createManualCase = v; return this; }

    public String externalId() { return externalId; }
    public String outcome() { return outcome; }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_id", externalId);
        m.put("outcome", outcome);
        m.put("name", name);
        m.put("started_on", startedOn);
        m.put("completed_on", completedOn);
        m.put("duration_ms", durationMs);
        m.put("message", message);
        m.put("traces", traces);
        // LIST of {name, value} - NOT a map (server camelCases keys).
        m.put("parameters", Payloads.payloads(parameters));
        m.put("step_results", Payloads.payloads(stepResults));
        m.put("setup_results", Payloads.payloads(setupResults));
        m.put("teardown_results", Payloads.payloads(teardownResults));
        m.put("attachments", Payloads.payloads(attachments));
        m.put("links", Payloads.payloads(links));
        if (createManualCase) {
            m.put("create_manual_case", true);
        }
        return Payloads.compact(m);
    }
}
