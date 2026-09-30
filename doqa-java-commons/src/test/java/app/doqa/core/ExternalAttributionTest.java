package app.doqa.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.doqa.annotations.DoqaCaseIds;
import app.doqa.annotations.DoqaClassName;
import app.doqa.annotations.DoqaId;
import app.doqa.annotations.DoqaNamespace;
import app.doqa.client.DoqaConfig;
import app.doqa.client.Json;
import io.qameta.allure.AllureId;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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

class ExternalAttributionTest {

    static class Annotated {
        @DoqaId("ANNOTATED-1")
        @AllureId("11")
        @DoqaCaseIds({1, 2})
        @DoqaNamespace("annotated.ns")
        @DoqaClassName("AnnotatedClass")
        void annotated() {
        }
    }

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final List<Logger> loggers = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };

    @BeforeEach
    void captureLog() {
        for (Class<?> c : new Class<?>[]{Attribution.class, DoqaSession.class}) {
            Logger logger = Logger.getLogger(c.getName());
            logger.addHandler(capture);
            loggers.add(logger);
        }
    }

    @AfterEach
    void cleanup() {
        loggers.forEach(l -> l.removeHandler(capture));
        DoqaSession.reset();
        DoqaSession.setConfigOverride(null);
        DoqaSession.setEnvOverride(null);
    }

    private static TestRef.Builder scenario() {
        return new TestRef.Builder()
                .fqcn("features/payments/transfer.feature")
                .methodName("Transfer <amount>")
                .displayName("Transfer <amount>")
                .signature("features/payments/transfer.feature#Transfer <amount>")
                .namespace("features/payments")
                .classname("Transfers")
                .framework("cucumber", "cucumber")
                .methodKey("classpath:features/payments/transfer.feature:12");
    }

    private static ResultBuilder.Built build(RuntimeContext ctx) {
        return ResultBuilder.build(ctx, "passed", null, null, null, null, null);
    }

    private static RuntimeContext ctx(TestRef ref) {
        RuntimeContext ctx = new RuntimeContext("uid");
        ctx.testRef = ref;
        return ctx;
    }

    private List<String> warnings() {
        return records.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage).collect(Collectors.toList());
    }

    private static String repeat(char c, int n) {
        return String.join("", Collections.nCopies(n, String.valueOf(c)));
    }

    @Test
    void explicitSignatureIsHashedAsIsUnderTheRefsFramework() {
        TestRef ref = scenario().build();
        Attribution.Result r = Attribution.resolve(ref);
        assertEquals(Attribution.Source.SIGNATURE_HASH, r.source);
        assertEquals("cucumber:" + SignatureHash.sha1Hex(
                "features/payments/transfer.feature#Transfer <amount>"), r.externalId);
        TestRef renamed = scenario().displayName("Transfer 10").build();
        assertEquals(r.externalId, Attribution.resolve(renamed).externalId,
                "the display name is not appended to an explicit signature");
    }

    @Test
    void declaredValuesFollowTheCascade() {
        assertEquals("PINNED-{amount}", Attribution.resolve(scenario()
                .explicitId(" PINNED-{amount} ").titleId("DOQA-5").allureId("7").build()).externalId);
        assertEquals("DOQA-5", Attribution.resolve(scenario()
                .titleId("DOQA-5").displayName("[DOQA-6] name").allureId("7").build()).externalId);
        assertEquals("DOQA-6", Attribution.resolve(scenario()
                .displayName("[DOQA-6] name").allureId("7").build()).externalId);
        Attribution.Result allure = Attribution.resolve(scenario().allureId("7").build());
        assertEquals("ALLURE-7", allure.externalId);
        assertEquals("7", allure.allureId);
        Attribution.Result pinned = Attribution.resolve(scenario()
                .explicitId("PINNED").allureId("7").caseIds(3, 4).build());
        assertEquals("7", pinned.allureId, "allure_id travels even when another id wins");
        assertArrayEquals(new long[]{3, 4}, pinned.caseIds);
    }

    @Test
    void annotationsWinOverDeclaredValuesAndCaseIdsJoin() throws Exception {
        Method m = Annotated.class.getDeclaredMethod("annotated");
        TestRef ref = new TestRef.Builder().fqcn(Annotated.class.getName()).methodName("annotated")
                .displayName("annotated()").testClass(Annotated.class).testMethod(m)
                .explicitId("DECLARED-1").allureId("22").caseIds(2, 3)
                .namespace("declared.ns").classname("DeclaredClass").build();
        Attribution.Result r = Attribution.resolve(ref);
        assertEquals("ANNOTATED-1", r.externalId);
        assertEquals("11", r.allureId);
        assertArrayEquals(new long[]{1, 2, 3}, r.caseIds);
        ResultBuilder.Built built = build(ctx(ref));
        assertEquals("annotated.ns", built.def.toPayload().get("namespace"));
        assertEquals("AnnotatedClass", built.def.toPayload().get("classname"));
    }

    @Test
    void explicitCoordinatesReplaceTheDottedSplit() {
        ResultBuilder.Built built = build(ctx(scenario().build()));
        Map<String, Object> def = built.def.toPayload();
        assertEquals("features/payments", def.get("namespace"));
        assertEquals("Transfers", def.get("classname"));
        assertEquals("Transfer <amount>", def.get("runner_method"));
        assertEquals("classpath:features/payments/transfer.feature:12", built.methodKey);
        assertEquals("cucumber", built.frameworkLabel);
    }

    @Test
    void plainRefKeepsTheJvmWideFrameworkAndDerivedCoordinates() {
        TestRef ref = new TestRef("com.acme.LoginTest", "works", "", "works()", false, null, null);
        assertEquals(AdapterRuntime.framework(), ref.framework());
        assertEquals(AdapterRuntime.frameworkLabel(), ref.frameworkLabel());
        assertEquals("com.acme.LoginTest#works", ref.methodKey());
        Map<String, Object> def = build(ctx(ref)).def.toPayload();
        assertEquals("com.acme", def.get("namespace"));
        assertEquals("LoginTest", def.get("classname"));
    }

    @Test
    void definitionCarriesTheTemplateAndTheResultTheSubstitutedValues() {
        RuntimeContext ctx = ctx(scenario().build());
        ctx.displayName = "Transfer 10";
        ctx.definitionName = "Transfer <amount>";
        StepNode step = new StepNode("When I send 10");
        step.definitionTitle = "When I send <amount>";
        step.outcome = "passed";
        ctx.callSteps.add(step);
        ResultBuilder.Built built = build(ctx);
        assertEquals("Transfer <amount>", built.def.toPayload().get("name"));
        assertEquals("Transfer 10", built.result.toPayload().get("name"));
        assertEquals("When I send <amount>", title(defSteps(built), 0));
        assertEquals("When I send 10", title(resultSteps(built), 0));
    }

    @Test
    void withoutDefinitionValuesBothSidesMatch() {
        RuntimeContext ctx = ctx(scenario().build());
        ctx.displayName = "Transfer 10";
        ctx.callSteps.add(new StepNode("When I send 10"));
        ResultBuilder.Built built = build(ctx);
        assertEquals("Transfer 10", built.def.toPayload().get("name"));
        assertEquals("When I send 10", title(defSteps(built), 0));
    }

    @Test
    void namesRunnerMethodAndStepTitlesAreClippedToServerLimits() {
        String longName = repeat('n', 300);
        RuntimeContext ctx = ctx(scenario().methodName(repeat('m', 300)).build());
        ctx.displayName = longName;
        StepNode parent = new StepNode(repeat('s', 600));
        parent.children.add(new StepNode(repeat('c', 501)));
        ctx.callSteps.add(parent);
        ResultBuilder.Built built = build(ctx);
        String defName = (String) built.def.toPayload().get("name");
        assertEquals(Limits.MAX_NAME, defName.length());
        assertTrue(defName.endsWith("…"));
        assertEquals(Limits.MAX_NAME, ((String) built.result.toPayload().get("name")).length());
        assertEquals(Limits.MAX_RUNNER_METHOD,
                ((String) built.def.toPayload().get("runner_method")).length());
        assertEquals(Limits.MAX_STEP_TITLE, title(defSteps(built), 0).length());
        assertEquals(Limits.MAX_STEP_TITLE, title(children(defSteps(built), 0), 0).length());
        assertEquals(Limits.MAX_STEP_TITLE, title(resultSteps(built), 0).length());
        assertEquals(Limits.MAX_STEP_TITLE, title(children(resultSteps(built), 0), 0).length());
    }

    @Test
    void clipNeverSplitsASurrogatePair() {
        String s = repeat('a', 3) + "😀" + "bbb";
        assertEquals("aaa…", Limits.clip(s, 5));
        assertEquals("abc", Limits.clip("abc", 3));
    }

    @Test
    void overlongIdFallsToTheNextBranchWithAWarning() {
        Attribution.Result r = Attribution.resolve(scenario()
                .explicitId(repeat('x', 256)).titleId("DOQA-5").build());
        assertEquals("DOQA-5", r.externalId);
        assertTrue(warnings().stream().anyMatch(w -> w.contains("longer than 255")), warnings().toString());

        Attribution.Result hash = Attribution.resolve(scenario().allureId(repeat('9', 300)).build());
        assertEquals(Attribution.Source.SIGNATURE_HASH, hash.source);
        assertEquals(repeat('9', 300), hash.allureId);
    }

    @Test
    void idThatOutgrowsTheLimitOnceSubstitutedFallsBackAtReportTime() {
        RuntimeContext ctx = ctx(scenario().explicitId("PAY-{amount}").allureId("7").build());
        ctx.invocationParameters.add(new Object[]{"amount", repeat('1', 300)});
        assertEquals("ALLURE-7", build(ctx).def.externalId());

        RuntimeContext fits = ctx(scenario().explicitId("PAY-{amount}").build());
        fits.invocationParameters.add(new Object[]{"amount", "10"});
        assertEquals("PAY-10", build(fits).def.externalId());
    }

    @Test
    void overlongRuntimeIdGivesWayToTheResolvedOne() {
        RuntimeContext ctx = ctx(scenario().titleId("DOQA-5").build());
        ctx.externalId = repeat('r', 256);
        assertEquals("DOQA-5", build(ctx).def.externalId());
        ctx.externalId = "RUNTIME-1";
        assertEquals("RUNTIME-1", build(ctx).def.externalId());
    }

    @Test
    void fileSinkLabelsEachResultWithItsOwnFramework(@TempDir Path dir) throws IOException {
        DoqaSession session = fileSession(dir);
        session.report(build(ctx(scenario().build())));
        session.report(build(ctx(new TestRef("com.acme.LoginTest", "works", "", "works()",
                false, null, null))));
        session.flush();
        List<String> labels = frameworkLabels(dir);
        Collections.sort(labels);
        List<String> expected = new ArrayList<>(List.of("cucumber", AdapterRuntime.frameworkLabel()));
        Collections.sort(expected);
        assertEquals(expected, labels);
    }

    @Test
    void asyncReportsAreBuiltOffTheCallerAndAwaitedByFlush(@TempDir Path dir) throws IOException {
        DoqaSession session = fileSession(dir);
        List<String> threads = new CopyOnWriteArrayList<>();
        String caller = Thread.currentThread().getName();
        for (int i = 0; i < 3; i++) {
            String name = "scenario " + i;
            session.reportAsync(() -> {
                threads.add(Thread.currentThread().getName());
                sleep(50);
                RuntimeContext ctx = ctx(scenario().build());
                ctx.displayName = name;
                return build(ctx);
            });
        }
        session.flush();
        assertEquals(3, resultFiles(dir).size(), "flush waits for every submitted build");
        assertEquals(1, threads.stream().distinct().count(), "one builder thread per JVM");
        assertNotEquals(caller, threads.get(0));
    }

    @Test
    void failingAsyncBuildIsDroppedWithAWarning(@TempDir Path dir) throws IOException {
        DoqaSession session = fileSession(dir);
        session.reportAsync(() -> {
            throw new IllegalStateException("broken build");
        });
        session.reportAsync(() -> null);
        session.reportAsync(() -> build(ctx(scenario().build())));
        session.flush();
        assertEquals(1, resultFiles(dir).size());
        assertTrue(warnings().stream().anyMatch(w -> w.contains("building a result failed")),
                warnings().toString());
    }

    @Test
    void awaitingWithoutAsyncReportsReturnsAtOnce() {
        DoqaSession.awaitPendingReports();
    }

    private static List<?> defSteps(ResultBuilder.Built built) {
        return (List<?>) built.def.toPayload().get("steps");
    }

    private static List<?> resultSteps(ResultBuilder.Built built) {
        return (List<?>) built.result.toPayload().get("step_results");
    }

    private static String title(List<?> steps, int i) {
        return (String) ((Map<?, ?>) steps.get(i)).get("title");
    }

    private static List<?> children(List<?> steps, int i) {
        return (List<?>) ((Map<?, ?>) steps.get(i)).get("steps");
    }

    private static DoqaSession fileSession(Path dir) {
        DoqaSession.reset();
        DoqaSession.setEnvOverride(Map.of());
        DoqaSession.setConfigOverride(new DoqaConfig.Builder()
                .reporting(DoqaConfig.REPORTING_FILES).resultsDir(dir.toString()).build());
        return DoqaSession.getOrInit();
    }

    private static List<Path> resultFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-result.json"))
                    .collect(Collectors.toList());
        }
    }

    private static List<String> frameworkLabels(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : resultFiles(dir)) {
            Map<String, Object> result = Json.parseObject(
                    new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            for (Object o : (List<?>) result.get("labels")) {
                Map<?, ?> label = (Map<?, ?>) o;
                if ("framework".equals(label.get("name"))) {
                    out.add(String.valueOf(label.get("value")));
                }
            }
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
