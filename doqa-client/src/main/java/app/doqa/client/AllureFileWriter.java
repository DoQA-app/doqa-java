package app.doqa.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File sink for the {@code reporting=files|auto} mode: serializes the SAME rich model the Direct
 * Autotest API consumes ({@link AutotestDef} + {@link AutotestResult}) into Allure-results files
 * the DoQA parser pipeline ingests - no network, no credentials.
 *
 * <p>Emitted per test:
 * <ul>
 *   <li>{@code <uuid>-result.json} - status/statusDetails/start/stop, native {@code description},
 *       DoQA labels ({@code doqa_id}, {@code doqa_cases}, {@code AS_ID},
 *       {@code doqa_title}, {@code doqa_create_manual_case}),
 *       {@code package}/{@code testClass}/{@code suite} (namespace/classname override),
 *       parameters (list), attachments ({@code {name, source, type}}),
 *       nested {@code steps} (test body);</li>
 *   <li>{@code <uuid>-container.json} - {@code befores}/{@code afters} fixture nodes referencing
 *       the result via {@code children} (setup/teardown results);</li>
 *   <li>attachment payload files copied next to the JSON and referenced by {@code source}.</li>
 * </ul>
 *
 * <p>The DoQA parser matches result files by name suffix ({@code *-result.json} /
 * {@code *-container.json}), so the directory name is free to configure ({@code resultsDir},
 * default {@code results/}).
 */
public final class AllureFileWriter {

    // Label names are part of the DoQA parser contract (matched case-insensitively there).
    public static final String LABEL_ID = "doqa_id";
    public static final String LABEL_CASES = "doqa_cases";
    public static final String LABEL_ALLURE_ID = "AS_ID";
    public static final String LABEL_CREATE_MANUAL_CASE = "doqa_create_manual_case";
    /** Human-readable title. Allure results have no dedicated title slot separate from
     *  name/fullName, so it travels as a label like the other {@code doqa_*} extensions. */
    public static final String LABEL_TITLE = "doqa_title";

    private final Path dir;
    private final String frameworkLabel;
    /** source file name -> [originalName, contentType]; populated by {@link #storeAttachment}. */
    private final Map<String, String[]> attachmentMeta = new ConcurrentHashMap<>();

    public AllureFileWriter(Path dir, String frameworkLabel) {
        this.dir = dir;
        this.frameworkLabel = frameworkLabel;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create results dir " + dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    /**
     * Copies a local file into the results dir under a unique {@code <uuid>-attachment.<ext>} name
     * and returns that name - the drop-in counterpart of the API sink's {@code uploadAttachment}
     * (the returned handle travels through {@link Attachment} and is resolved back to
     * {@code {name, source, type}} at serialization time).
     */
    public String storeAttachment(String path) {
        Path src = Paths.get(path);
        String original = src.getFileName().toString();
        String source = UUID.randomUUID() + "-attachment" + extension(original);
        try {
            Files.copy(src, dir.resolve(source), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy attachment " + path, e);
        }
        attachmentMeta.put(source, new String[]{original, ContentTypes.of(original)});
        return source;
    }

    /** Stores in-memory content as an attachment - counterpart of the byte[] API upload. */
    public String storeAttachment(String name, byte[] content, String contentType) {
        String original = name == null ? "attachment" : name;
        String source = UUID.randomUUID() + "-attachment" + extension(original);
        try {
            Files.write(dir.resolve(source), content == null ? new byte[0] : content);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write attachment " + original, e);
        }
        attachmentMeta.put(source, new String[]{original,
                contentType != null ? contentType : ContentTypes.of(original)});
        return source;
    }

    /** Drops attachment name/type metadata - call once per plan, after the last serialization. */
    public void clearAttachmentMeta() {
        attachmentMeta.clear();
    }

    /**
     * Writes {@code environment.properties} - the environment label channel of the file sink
     * (the Allure-results convention; the DoQA pipeline attaches the label to the run).
     */
    public void writeEnvironment(String environment) {
        if (environment == null || environment.trim().isEmpty()) {
            return;
        }
        try {
            Files.write(dir.resolve("environment.properties"),
                    ("environment=" + environment.trim() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write environment.properties", e);
        }
    }

    /**
     * Writes {@code <uuid>-result.json} (+ a fixtures container when setup/teardown results are
     * present) for one finished test and returns the result uuid (callers may reference it from a
     * shared {@link #writeContainer} later). {@code fullName} and {@code allureId} come from the
     * adapter (they are not part of the wire model).
     */
    public String write(AutotestDef def, AutotestResult result, String fullName, String allureId) {
        Map<String, Object> defMap = def.toPayload();
        Map<String, Object> resMap = result.toPayload();
        String uuid = UUID.randomUUID().toString();

        Map<String, Object> allure = new LinkedHashMap<>();
        allure.put("uuid", uuid);
        allure.put("historyId", resMap.get("external_id"));
        allure.put("name", resMap.get("name"));
        if (fullName != null) {
            allure.put("fullName", fullName);
        }
        // description is a native Allure-result field (unlike title, it has a slot of its own).
        Payloads.putIfPresent(allure, "description", defMap.get("description"));
        allure.put("status", resMap.get("outcome"));
        Map<String, Object> details = new LinkedHashMap<>();
        Payloads.putIfPresent(details, "message", resMap.get("message"));
        Payloads.putIfPresent(details, "trace", resMap.get("traces"));
        if (!details.isEmpty()) {
            allure.put("statusDetails", details);
        }
        allure.put("start", resMap.get("started_on"));
        allure.put("stop", resMap.get("completed_on"));
        allure.put("labels", labels(defMap, resMap, allureId));
        allure.put("parameters", orEmpty(resMap.get("parameters")));
        allure.put("attachments", attachments(resMap.get("attachments")));
        allure.put("steps", steps(resMap.get("step_results")));
        allure.put("links", orEmpty(defMap.get("links")));

        writeJson(dir.resolve(uuid + "-result.json"), allure);

        List<Object> befores = steps(resMap.get("setup_results"));
        List<Object> afters = steps(resMap.get("teardown_results"));
        if (!befores.isEmpty() || !afters.isEmpty()) {
            Map<String, Object> container = new LinkedHashMap<>();
            String containerUuid = UUID.randomUUID().toString();
            container.put("uuid", containerUuid);
            container.put("children", List.of(uuid));
            container.put("befores", befores);
            container.put("afters", afters);
            writeJson(dir.resolve(containerUuid + "-container.json"), container);
        }
        return uuid;
    }

    /**
     * Writes a shared fixtures container covering several results - the class-level
     * (@BeforeAll/@AfterAll) fixtures of a JUnit-style suite. The parser attaches
     * {@code befores}/{@code afters} to every child result (same aggregation as Allure).
     */
    public void writeContainer(List<String> childrenUuids, List<StepResult> befores,
                               List<StepResult> afters) {
        if (childrenUuids == null || childrenUuids.isEmpty()
                || ((befores == null || befores.isEmpty()) && (afters == null || afters.isEmpty()))) {
            return;
        }
        Map<String, Object> container = new LinkedHashMap<>();
        String containerUuid = UUID.randomUUID().toString();
        container.put("uuid", containerUuid);
        container.put("children", new ArrayList<>(childrenUuids));
        container.put("befores", steps(Payloads.payloads(befores)));
        container.put("afters", steps(Payloads.payloads(afters)));
        writeJson(dir.resolve(containerUuid + "-container.json"), container);
    }

    // ------------------------------------------------------------------ transforms
    private List<Map<String, Object>> labels(Map<String, Object> defMap,
                                             Map<String, Object> resMap,
                                             String allureId) {
        List<Map<String, Object>> labels = new ArrayList<>();
        addLabel(labels, LABEL_TITLE, defMap.get("title"));
        addLabel(labels, LABEL_ID, defMap.get("external_id"));
        Object cases = defMap.get("case_ids");
        if (cases instanceof List && !((List<?>) cases).isEmpty()) {
            StringBuilder csv = new StringBuilder();
            for (Object id : (List<?>) cases) {
                if (csv.length() > 0) {
                    csv.append(',');
                }
                csv.append(id);
            }
            addLabel(labels, LABEL_CASES, csv.toString());
        }
        addLabel(labels, LABEL_ALLURE_ID, allureId);
        addLabel(labels, LABEL_CREATE_MANUAL_CASE, resMap.get("create_manual_case"));
        addLabel(labels, "framework", frameworkLabel);
        addLabel(labels, "language", "java");
        addLabel(labels, "package", defMap.get("namespace"));
        addLabel(labels, "testClass", defMap.get("classname"));
        addLabel(labels, "suite", defMap.get("classname"));
        for (String key : new String[]{"tags", "labels"}) {
            Object values = defMap.get(key);
            if (values instanceof List) {
                for (Object v : (List<?>) values) {
                    addLabel(labels, "tag", v);
                }
            }
        }
        return labels;
    }

    /** {@code step_results} node → Allure step node (recursive). */
    @SuppressWarnings("unchecked")
    private List<Object> steps(Object stepResults) {
        List<Object> out = new ArrayList<>();
        if (!(stepResults instanceof List)) {
            return out;
        }
        for (Object raw : (List<Object>) stepResults) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) raw;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", node.get("title"));
            step.put("status", node.get("outcome"));
            Map<String, Object> details = new LinkedHashMap<>();
            Payloads.putIfPresent(details, "message", node.get("message"));
            if (!details.isEmpty()) {
                step.put("statusDetails", details);
            }
            Payloads.putIfPresent(step, "start", node.get("started_on"));
            Payloads.putIfPresent(step, "stop", node.get("completed_on"));
            step.put("attachments", attachments(node.get("attachments")));
            step.put("steps", steps(node.get("steps")));
            out.add(step);
        }
        return out;
    }

    /** {@code [{media_file_id: <source>}]} → Allure {@code [{name, source, type}]}. */
    @SuppressWarnings("unchecked")
    private List<Object> attachments(Object wire) {
        List<Object> out = new ArrayList<>();
        if (!(wire instanceof List)) {
            return out;
        }
        for (Object raw : (List<Object>) wire) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Object source = ((Map<String, Object>) raw).get("media_file_id");
            if (source == null) {
                continue;
            }
            // NOT removed here: class-fixture attachments are referenced by every result of the
            // class; the session clears the whole map at plan end (clearAttachmentMeta).
            String[] meta = attachmentMeta.get(String.valueOf(source));
            Map<String, Object> att = new LinkedHashMap<>();
            att.put("name", meta != null ? meta[0] : String.valueOf(source));
            att.put("source", String.valueOf(source));
            att.put("type", meta != null ? meta[1] : "application/octet-stream");
            out.add(att);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private void writeJson(Path path, Map<String, Object> map) {
        try {
            Files.write(path, Json.write(map).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    private static void addLabel(List<Map<String, Object>> labels, String name, Object value) {
        if (value == null || String.valueOf(value).isEmpty()) {
            return;
        }
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("name", name);
        label.put("value", String.valueOf(value));
        labels.add(label);
    }

    private static List<Object> orEmpty(Object value) {
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            return list;
        }
        return new ArrayList<>();
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".bin";
    }
}
