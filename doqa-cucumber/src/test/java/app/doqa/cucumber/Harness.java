package app.doqa.cucumber;

import app.doqa.client.Json;
import app.doqa.core.DoqaSession;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.testng.TestNG;

final class Harness implements AutoCloseable {

    static final String GLUE = "app.doqa.cucumber.e2e.steps";
    static final String PLUGIN = DoqaCucumberPlugin.class.getName();

    static final class Recorded {
        final String method;
        final String path;
        final String body;

        Recorded(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }

    final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    volatile String selectiveResponse = "{\"autotests\":[]}";
    volatile int testRunStatus = 200;
    private final HttpServer server;
    private final List<String> props = new ArrayList<>();

    Harness() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            recorded.add(new Recorded(method, path, body));
            int status = 200;
            String response = "{}";
            if (path.endsWith("/test-runs")) {
                status = testRunStatus;
                response = "{\"runId\":4242}";
            } else if ("GET".equals(method) && path.endsWith("/autotests")) {
                status = testRunStatus;
                response = selectiveResponse;
            } else if (path.endsWith("/upsert")) {
                response = "{\"map\":{}}";
            } else if (path.endsWith("/attachments")) {
                response = "{\"mediaFileId\":555}";
            } else if (path.endsWith("/results")) {
                response = "{\"accepted\":1,\"elementIds\":[]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        DoqaSession.setEnvOverride(Map.of());
        set("doqa.config", "target/no-doqa.properties");
        set("cucumber.publish.quiet", "true");
    }

    Harness configure(Map<String, String> extra) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("doqa.url", "http://127.0.0.1:" + server.getAddress().getPort());
        all.put("doqa.token", "E2E-TOKEN");
        all.put("doqa.spaceId", "31");
        all.put("doqa.reporting", "api");
        all.put("doqa.resultsDir", "target/doqa-e2e-results");
        all.put("doqa.retries", "0");
        all.putAll(extra);
        all.forEach(this::set);
        DoqaSession.reset();
        return this;
    }

    void set(String key, String value) {
        System.setProperty(key, value);
        props.add(key);
    }

    @Override
    public void close() {
        for (String p : props) {
            System.clearProperty(p);
        }
        DoqaSession.reset();
        DoqaSession.setClientFactory(null);
        DoqaSession.setConfigOverride(null);
        DoqaSession.setEnvOverride(null);
        server.stop(0);
    }

    static TestExecutionSummary platform(Map<String, String> config, DiscoverySelector... selectors) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .configurationParameter("cucumber.glue", GLUE)
                .configurationParameter("cucumber.plugin", PLUGIN);
        config.forEach(builder::configurationParameter);
        return run(builder.build());
    }

    static TestExecutionSummary run(LauncherDiscoveryRequest request) {
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, summary);
        return summary.getSummary();
    }

    static org.junit.runner.Result junit4(Class<?> runner) {
        return org.junit.runner.JUnitCore.runClasses(runner);
    }

    static int testng(Class<?> runner) {
        TestNG testng = new TestNG(false);
        testng.setTestClasses(new Class<?>[] {runner});
        testng.setVerbose(0);
        testng.setOutputDirectory("target/testng-e2e");
        testng.run();
        return testng.getStatus();
    }

    List<Map<String, Object>> defs() {
        return items("/upsert", "autotests");
    }

    List<Map<String, Object>> results() {
        return items("/results", "results");
    }

    List<Recorded> requests(String method, String suffix) {
        List<Recorded> out = new ArrayList<>();
        for (Recorded r : recorded) {
            if (r.method.equals(method) && r.path.endsWith(suffix)) {
                out.add(r);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String suffix, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Recorded r : requests("POST", suffix)) {
            Object list = Json.parseObject(r.body).get(key);
            if (list instanceof List) {
                for (Object o : (List<Object>) list) {
                    out.add((Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    static Map<String, Object> byName(List<Map<String, Object>> items, String name) {
        Map<String, Object> found = null;
        for (Map<String, Object> m : items) {
            if (name.equals(m.get("name"))) {
                if (found != null) {
                    throw new AssertionError("several items named " + name);
                }
                found = m;
            }
        }
        if (found == null) {
            throw new AssertionError("no item named " + name + " in " + names(items));
        }
        return found;
    }

    static List<Map<String, Object>> allNamed(List<Map<String, Object>> items, String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : items) {
            if (name.equals(m.get("name"))) {
                out.add(m);
            }
        }
        return out;
    }

    static List<Object> names(List<Map<String, Object>> items) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> m : items) {
            out.add(m.get("name"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    // forgets the JVM-wide plugin flag so the missing-plugin path is tested too
    static void forgetPluginCreated() {
        try {
            Field f = app.doqa.core.AdapterRuntime.class.getDeclaredField("cucumberPluginCreated");
            f.setAccessible(true);
            f.setBoolean(null, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
