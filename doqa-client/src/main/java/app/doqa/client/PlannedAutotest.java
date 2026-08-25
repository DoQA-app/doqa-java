package app.doqa.client;

import java.util.Map;

/**
 * One entry of a selective run's plan ({@code GET test-runs/{id}/autotests}): the selected autotest
 * and the runner identity DoQA holds for it (namespace / classname / method).
 */
public final class PlannedAutotest {

    private final String externalId;
    private final String namespace;
    private final String classname;
    private final String runnerMethod;

    public PlannedAutotest(String externalId, String namespace, String classname,
                           String runnerMethod) {
        this.externalId = externalId;
        this.namespace = namespace;
        this.classname = classname;
        this.runnerMethod = runnerMethod;
    }

    public String externalId() { return externalId; }
    public String namespace() { return namespace; }
    public String classname() { return classname; }
    public String runnerMethod() { return runnerMethod; }

    /** Reads one plan entry; both key spellings of the contract are accepted. */
    static PlannedAutotest fromPayload(Map<String, Object> entry) {
        String externalId = str(entry, "externalId", "external_id");
        if (externalId == null) {
            return null;
        }
        return new PlannedAutotest(externalId, str(entry, "namespace", "namespace"),
                str(entry, "classname", "classname"), str(entry, "runnerMethod", "runner_method"));
    }

    private static String str(Map<String, Object> entry, String camel, String snake) {
        Object value = entry.containsKey(camel) ? entry.get(camel) : entry.get(snake);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
