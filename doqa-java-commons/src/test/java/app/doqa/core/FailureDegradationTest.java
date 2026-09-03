package app.doqa.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.doqa.client.ApiClient;
import app.doqa.client.AutotestDef;
import app.doqa.client.AutotestResult;
import app.doqa.client.DoqaConfig;
import app.doqa.client.Outcome;
import app.doqa.client.Transport;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A DoQA that fails never costs the results: a rejected establish degrades the whole session to
 * the file sink, a rejected results chunk lands on disk while later chunks keep using the API, and
 * the reporting-info file says which of the two happened.
 */
class FailureDegradationTest {

    /** Canned backend: any endpoint can be told to answer a status or to drop the connection. */
    static final class FakeTransport implements Transport {
        final List<String> bodies = new ArrayList<>();
        volatile int planStatus = 200;
        volatile int createStatus = 200;
        volatile int resultsStatus = 200;
        volatile boolean unreachable;

        @Override
        public Response send(Request request) throws IOException {
            if (unreachable) {
                throw new ConnectException("Connection refused");
            }
            if (request.jsonBody != null) {
                bodies.add(request.jsonBody);
            }
            if (request.url.contains("/test-runs/") && request.url.contains("/autotests")) {
                return new Response(planStatus, planStatus == 200
                        ? "{\"autotests\":[{\"externalId\":\"SEL-1\"}]}"
                        : "{\"error\":{\"message\":\"Invalid token\"}}");
            }
            if (request.url.endsWith("/test-runs")) {
                return new Response(createStatus, createStatus == 200
                        ? "{\"runId\":\"RUN-1\"}"
                        : "{\"error\":{\"message\":\"An active CI binding is required\"}}");
            }
            if (request.url.endsWith("/upsert")) {
                return new Response(200, "{\"map\":{}}");
            }
            if (request.url.endsWith("/results")) {
                return new Response(resultsStatus, resultsStatus == 200 ? "{\"accepted\":1}" : "boom");
            }
            return new Response(200, "{}");
        }

        long count(String needle) {
            return bodies.stream().filter(b -> b.contains(needle)).count();
        }
    }

    private final List<LogRecord> records = new ArrayList<>();
    private final Logger sessionLog = Logger.getLogger(DoqaSession.class.getName());
    private Handler capture;

    @BeforeEach
    void captureLog() {
        capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        sessionLog.addHandler(capture);
        sessionLog.setLevel(Level.ALL);
    }

    @AfterEach
    void cleanup() {
        sessionLog.removeHandler(capture);
        DoqaSession.reset();
        DoqaSession.setConfigOverride(null);
        DoqaSession.setClientFactory(null);
    }

    private static DoqaSession session(FakeTransport t, DoqaConfig.Builder cfg) {
        DoqaSession.reset();
        DoqaSession.setConfigOverride(cfg.build());
        DoqaSession.setClientFactory(c -> new ApiClient(c, t, 1, 0));
        return DoqaSession.getOrInit();
    }

    private static DoqaConfig.Builder apiConfig(Path dir) {
        return new DoqaConfig.Builder()
                .url("https://doqa.example").token("T").spaceId("S").resultsDir(dir.toString());
    }

    private static ResultBuilder.Built built(String externalId) {
        return new ResultBuilder.Built(new AutotestDef(externalId, "n"),
                new AutotestResult(externalId, Outcome.PASSED), null, null, null, "fixture#" + externalId);
    }

    private List<String> warnings() {
        return records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage)
                .collect(Collectors.toList());
    }

    private static List<Path> resultFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-result.json"))
                    .collect(Collectors.toList());
        }
    }

    private static String reportingInfo(Path dir) throws IOException {
        return new String(Files.readAllBytes(dir.resolve("doqa-reporting.properties")),
                StandardCharsets.UTF_8);
    }

    @Test
    void rejectedTokenOnTheSelectivePlanDegradesToFilesAndRunsEverything(@TempDir Path dir)
            throws IOException {
        FakeTransport t = new FakeTransport();
        t.planStatus = 401;

        DoqaSession session = session(t, apiConfig(dir).adapterMode(0).testRunId("RUN-9"));

        assertTrue(session.enabled, "a rejected establish must not disable reporting");
        assertTrue(session.fileSink());
        assertNull(session.runContext, "no plan: no selection");
        assertTrue(session.allowsId("ANY"), "without a plan every test is in scope");
        assertFalse(DoqaSession.discoverySelectionActive());

        session.report(built("SEL-1"));
        session.report(built("OTHER-2"));
        session.flush();

        assertEquals(2, resultFiles(dir).size(), "every result lands on disk");
        assertEquals(0, t.count("\"results\""), "nothing is uploaded with a rejected token");
        String info = reportingInfo(dir);
        assertTrue(info.contains("sink=files"), info);
        assertTrue(info.contains("degradedFrom=api"), info);

        String warning = warnings().get(0);
        assertTrue(warning.contains("could not establish the test run"), warning);
        assertTrue(warning.contains("401"), warning);
        assertTrue(warning.contains(dir.toString()), warning);
        assertTrue(warning.contains("every discovered test runs"), warning);
    }

    @Test
    void refusedRunCreationDegradesToFiles(@TempDir Path dir) throws IOException {
        FakeTransport t = new FakeTransport();
        t.createStatus = 422;

        DoqaSession session = session(t, apiConfig(dir).adapterMode(2));
        session.report(built("T-1"));
        session.flush();

        assertTrue(session.enabled);
        assertTrue(session.fileSink());
        assertEquals(1, resultFiles(dir).size());
        String warning = warnings().get(0);
        assertTrue(warning.contains("422"), warning);
        assertTrue(warning.contains("An active CI binding is required"), warning);
        assertTrue(warning.contains("NOT sent to DoQA directly"), warning);
    }

    @Test
    void unreachableServerAtStartDegradesToFiles(@TempDir Path dir) throws IOException {
        FakeTransport t = new FakeTransport();
        t.unreachable = true;

        DoqaSession session = session(t, apiConfig(dir).adapterMode(2));
        session.report(built("T-1"));
        session.report(built("T-2"));
        session.flush();

        assertTrue(session.enabled);
        assertTrue(session.fileSink());
        assertEquals(2, resultFiles(dir).size());
        String warning = warnings().get(0);
        assertTrue(warning.contains("Connection refused"), warning);
        assertTrue(warning.contains("did not answer at https://doqa.example"), warning);
    }

    @Test
    void rejectedChunkLandsOnDiskWhileLaterChunksKeepTheApi(@TempDir Path dir) throws IOException {
        FakeTransport t = new FakeTransport();
        DoqaSession session = session(t, apiConfig(dir).adapterMode(2).importRealtime(true));
        assertFalse(session.fileSink());
        assertTrue(reportingInfo(dir).contains("sink=api"), "an api session announces itself up front");

        session.report(new ResultBuilder.Built(new AutotestDef("A-1", "a"),
                new AutotestResult("A-1", Outcome.PASSED), null, null, "com.x.One", "com.x.One#a"));
        session.report(new ResultBuilder.Built(new AutotestDef("B-1", "b"),
                new AutotestResult("B-1", Outcome.PASSED), null, null, "com.x.Two", "com.x.Two#b"));

        t.resultsStatus = 500;
        session.flushClass("com.x.One");   // the link is down for this class only
        t.resultsStatus = 200;
        session.flushClass("com.x.Two");
        session.flush();

        assertEquals(2, t.count("\"results\""), "both classes were attempted over the API");
        List<Path> onDisk = resultFiles(dir);
        assertEquals(1, onDisk.size(), "only the rejected chunk is written as files");
        String file = new String(Files.readAllBytes(onDisk.get(0)), StandardCharsets.UTF_8);
        assertTrue(file.contains("A-1"), file);

        String info = reportingInfo(dir);
        assertTrue(info.contains("sink=api"), info);
        assertTrue(info.contains("runId=RUN-1"), info);
        assertTrue(info.contains("delivered=1"), info);
        assertTrue(info.contains("fallbackResults=1"), info);

        String warning = warnings().get(0);
        assertTrue(warning.contains("results chunk failed"), warning);
        assertTrue(warning.contains("1 of 1 result(s) written as Allure files"), warning);
    }

    @Test
    void apiSessionLeavesNoResultFilesWhenEverythingIsDelivered(@TempDir Path dir) throws IOException {
        FakeTransport t = new FakeTransport();
        DoqaSession session = session(t, apiConfig(dir).adapterMode(2));
        session.report(built("T-1"));
        session.flush();

        assertTrue(resultFiles(dir).isEmpty());
        String info = reportingInfo(dir);
        assertTrue(info.contains("sink=api"), info);
        assertTrue(info.contains("delivered=1"), info);
        assertTrue(info.contains("fallbackResults=0"), info);
        assertTrue(warnings().isEmpty(), "a healthy api session is quiet: " + warnings());
    }
}
