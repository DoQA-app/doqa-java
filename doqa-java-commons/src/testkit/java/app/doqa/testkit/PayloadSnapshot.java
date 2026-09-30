package app.doqa.testkit;

import app.doqa.client.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Golden-file snapshot of what an adapter sends; {@code -Ddoqa.regression.record=true} re-records. */
public final class PayloadSnapshot {

    private static final Set<String> VOLATILE_KEYS =
            Set.of("started_on", "completed_on", "duration_ms", "traces");

    private PayloadSnapshot() {
    }

    public static List<String> api(FakeBackend backend) {
        Set<String> defs = new TreeSet<>();
        for (String body : backend.upserts) {
            for (Object def : list(Json.parseObject(body).get("autotests"))) {
                defs.add("def " + Json.write(canonical(def)));
            }
        }
        List<String> results = new ArrayList<>();
        for (String body : backend.results) {
            for (Object result : list(Json.parseObject(body).get("results"))) {
                results.add("result " + Json.write(canonical(result)));
            }
        }
        results.sort(null);
        List<String> out = new ArrayList<>(defs);
        out.addAll(results);
        return out;
    }

    public static List<String> files(Path dir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("-result.json"))
                    .collect(Collectors.toList())) {
                Map<String, Object> result = Json.parseObject(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                String framework = null;
                String id = null;
                for (Object o : list(result.get("labels"))) {
                    Map<?, ?> label = (Map<?, ?>) o;
                    if ("framework".equals(label.get("name"))) {
                        framework = String.valueOf(label.get("value"));
                    } else if ("doqa_id".equals(label.get("name"))) {
                        id = String.valueOf(label.get("value"));
                    }
                }
                out.add("file framework=" + framework + " id=" + id + " name=" + result.get("name")
                        + " fullName=" + result.get("fullName"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(null);
        return out;
    }

    public static void assertGolden(String name, List<String> lines) {
        Path golden = Paths.get("src", "test", "resources", "regression", name + ".golden");
        String actual = String.join("\n", lines) + "\n";
        try {
            boolean missing = !Files.exists(golden);
            if (missing || Boolean.getBoolean("doqa.regression.record")) {
                Files.createDirectories(golden.getParent());
                Files.write(golden, actual.getBytes(StandardCharsets.UTF_8));
                if (missing) {
                    throw new AssertionError("golden " + golden + " was missing and has been recorded;"
                            + " review it and re-run");
                }
                return;
            }
            String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            if (!expected.equals(actual)) {
                Path dump = Paths.get("target", "regression", name + ".actual");
                Files.createDirectories(dump.getParent());
                Files.write(dump, actual.getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("reported payload differs from " + golden + " (actual: "
                        + dump + ")\n" + firstDifference(expected, actual));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String firstDifference(String expected, String actual) {
        List<String> e = Arrays.asList(expected.split("\n"));
        List<String> a = Arrays.asList(actual.split("\n"));
        for (int i = 0; i < Math.max(e.size(), a.size()); i++) {
            String el = i < e.size() ? e.get(i) : "<none>";
            String al = i < a.size() ? a.get(i) : "<none>";
            if (!el.equals(al)) {
                return "line " + (i + 1) + "\n  expected: " + el + "\n  actual:   " + al;
            }
        }
        return "";
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!VOLATILE_KEYS.contains(key)) {
                    sorted.put(key, canonical(e.getValue()));
                }
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) value) {
                out.add(canonical(o));
            }
            return out;
        }
        return value;
    }

    private static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : List.of();
    }
}
