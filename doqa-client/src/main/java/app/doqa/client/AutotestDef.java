package app.doqa.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One autotest definition (element of the {@code upsert} payload). */
public final class AutotestDef implements Model {

    private String externalId;
    private String name;
    private String title;
    private String description;
    private String namespace;
    private String classname;
    private String runnerMethod;
    private final List<String> labels = new ArrayList<>();
    private final List<String> tags = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private final List<Long> caseIds = new ArrayList<>();

    public AutotestDef(String externalId, String name) {
        this.externalId = externalId;
        this.name = name;
    }

    public AutotestDef externalId(String v) { this.externalId = v; return this; }
    public AutotestDef name(String v) { this.name = v; return this; }
    public AutotestDef title(String v) { this.title = v; return this; }
    public AutotestDef description(String v) { this.description = v; return this; }
    public AutotestDef namespace(String v) { this.namespace = v; return this; }
    public AutotestDef classname(String v) { this.classname = v; return this; }
    public AutotestDef runnerMethod(String v) { this.runnerMethod = v; return this; }
    public AutotestDef labels(List<String> v) { if (v != null) labels.addAll(v); return this; }
    public AutotestDef tags(List<String> v) { if (v != null) tags.addAll(v); return this; }
    public AutotestDef links(List<Link> v) { if (v != null) links.addAll(v); return this; }
    public AutotestDef steps(List<Step> v) { if (v != null) steps.addAll(v); return this; }
    public AutotestDef caseIds(List<Long> v) { if (v != null) caseIds.addAll(v); return this; }

    public String externalId() { return externalId; }
    public String name() { return name; }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_id", externalId);
        m.put("name", name);
        m.put("title", title);
        m.put("description", description);
        m.put("namespace", namespace);
        m.put("classname", classname);
        m.put("runner_method", runnerMethod);
        m.put("labels", new ArrayList<>(labels));
        m.put("tags", new ArrayList<>(tags));
        m.put("links", Payloads.payloads(links));
        m.put("steps", Payloads.payloads(steps));
        m.put("case_ids", new ArrayList<>(caseIds));
        return Payloads.compact(m);
    }
}
