package app.doqa.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.doqa.Doqa;
import app.doqa.annotations.DoqaCaseIds;
import app.doqa.annotations.DoqaCreateManualCase;
import app.doqa.annotations.DoqaId;
import app.doqa.annotations.DoqaLabels;
import app.doqa.annotations.DoqaLink;
import app.doqa.annotations.DoqaTitle;
import app.doqa.client.AllureFileWriter;
import app.doqa.client.Json;
import io.qameta.allure.AllureId;
import io.qameta.allure.Epic;
import io.qameta.allure.Issue;
import io.qameta.allure.Owner;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract fixtures for the framework-agnostic core: attribution cascade (explicit id /
 * id-in-title / reflective Allure bridge / signature hash), Allure-annotation migration into
 * labels/links, step-stack semantics and truncation. No engine, no network.
 */
class CoreContractTest {

    /** Attribution/meta fixture (annotations only, never executed). */
    @DoqaLabels({"regression"})
    static class Annotated {

        @DoqaId("DOQA-42")
        @DoqaTitle("Login works")
        @DoqaCaseIds({101, 102})
        @DoqaLink(url = "http://bug/1", type = "defect")
        @DoqaCreateManualCase
        void explicit() {
        }

        @AllureId("777")
        @Epic("checkout")
        @Owner("jane")
        @Severity(SeverityLevel.CRITICAL)
        @Issue("https://tracker/BUG-9")
        void allureAnnotated() {
        }

        @DoqaLink(url = "http://x", type = "defekt")
        void typoLinkType() {
        }

        void bare() {
        }
    }

    @AfterEach
    void cleanup() {
        DoqaSession.reset();
    }

    private static TestRef ref(String method, String displayName, boolean parameterized)
            throws NoSuchMethodException {
        Method m = Annotated.class.getDeclaredMethod(method);
        return new TestRef(Annotated.class.getName(), method, "", displayName, parameterized,
                Annotated.class, m);
    }

    // ------------------------------------------------------------------ attribution
    @Test
    void explicitExternalIdWins() throws Exception {
        Attribution.Result r = Attribution.resolve(ref("explicit", "login happy path", false));
        assertEquals("DOQA-42", r.externalId);
        assertEquals(Attribution.Source.EXPLICIT_EXTERNAL_ID, r.source);
        assertEquals(2, r.caseIds.length);
    }

    @Test
    void idInTitleFormsAreParsed() {
        assertEquals("DOQA-7", Attribution.extractIdInTitle("[DOQA-7] scenario"));
        assertEquals("DOQA-9", Attribution.extractIdInTitle("does @DOQA:9 thing"));
        assertNull(Attribution.extractIdInTitle("no id here"));
    }

    @Test
    void reflectiveAllureIdBridges() throws Exception {
        Attribution.Result r = Attribution.resolve(ref("allureAnnotated", "n", false));
        assertEquals("ALLURE-777", r.externalId);
        assertEquals(Attribution.Source.NATIVE_ALLURE, r.source);
        assertEquals("777", r.allureId);
    }

    @Test
    void fallbackHashIsDeterministicAndFrameworkPrefixed() throws Exception {
        AdapterRuntime.configure("junit5", "junit-platform");
        Attribution.Result r = Attribution.resolve(ref("bare", "bare()", false));
        assertEquals(Attribution.Source.SIGNATURE_HASH, r.source);
        assertTrue(r.externalId.startsWith("junit5:"), r.externalId);
        assertEquals(r.externalId, Attribution.resolve(ref("bare", "bare()", false)).externalId);
    }

    @Test
    void displayNamesDifferingOnlyByIndexStayDistinct() throws Exception {
        // dynamic tests "[1] проверка" / "[2] проверка" are DIFFERENT tests - the display name
        // is hashed verbatim, no index stripping
        Attribution.Result first = Attribution.resolve(ref("bare", "[1] проверка", false));
        Attribution.Result second = Attribution.resolve(ref("bare", "[2] проверка", false));
        assertNotEquals(first.externalId, second.externalId);
    }

    @Test
    void parameterizedCollapsesToMethodLevel() throws Exception {
        Attribution.Result a = Attribution.resolve(ref("bare", "[1] browser=chrome", true));
        Attribution.Result b = Attribution.resolve(ref("bare", "[2] browser=firefox", true));
        assertEquals(a.externalId, b.externalId, "invocations share the method-level id");
    }

    // ------------------------------------------------------------------ Allure bridge
    @Test
    void allureAnnotationsMapToLabelsAndLinks() throws Exception {
        Meta meta = MetaReader.read(ref("allureAnnotated", "n", false));
        assertTrue(meta.labels.contains("epic:checkout"), String.valueOf(meta.labels));
        assertTrue(meta.labels.contains("owner:jane"));
        assertTrue(meta.labels.contains("severity:critical"));
        assertTrue(meta.labels.contains("regression"), "native @DoqaLabels kept");
        assertEquals(1, meta.links.size());
        assertEquals("https://tracker/BUG-9", meta.links.get(0).url());
        assertEquals("defect", meta.links.get(0).type(), "@Issue maps to a defect link");
    }

    @Test
    void unknownDoqaLinkTypeIsDroppedNotSent() throws Exception {
        Meta meta = MetaReader.read(ref("typoLinkType", "n", false));
        assertEquals(1, meta.links.size());
        assertNull(meta.links.get(0).type(), "typo type must not travel to the wire");
    }

    // ------------------------------------------------------------------ steps
    @Test
    void failedStepKeepsBothUserNoteAndFailureMessage() {
        RuntimeContext ctx = DoqaContexts.open("uid-steps");
        try {
            ctx.phase = RuntimeContext.Phase.CALL;
            try {
                Doqa.step("boom step", () -> {
                    Doqa.addMessage("useful note");
                    throw new IllegalStateException("kaboom");
                });
            } catch (IllegalStateException expected) {
                // rethrown by the step
            }
            StepNode node = ctx.callSteps.get(0);
            assertEquals("broken", node.outcome);
            assertTrue(node.message.contains("useful note"), node.message);
            assertTrue(node.message.contains("kaboom"), "failure cause must not be lost: " + node.message);
        } finally {
            DoqaContexts.remove("uid-steps");
        }
    }

    @Test
    void checkpointStepIsInstantAndPassed() {
        RuntimeContext ctx = DoqaContexts.open("uid-cp");
        try {
            Doqa.step("checkpoint reached");
            assertEquals(1, ctx.callSteps.size());
            assertEquals("passed", ctx.callSteps.get(0).outcome);
        } finally {
            DoqaContexts.remove("uid-cp");
        }
    }

    @Test
    void closedContextRejectsLateWrites() {
        RuntimeContext ctx = DoqaContexts.open("uid-closed");
        DoqaContexts.remove("uid-closed");
        Doqa.addMessage("late");
        assertTrue(ctx.messages.isEmpty(), "a removed context must not accept new data");
        assertNull(DoqaContexts.current(), "stale thread-local cleared on access");
    }

    @Test
    void contextTransferCarriesStepsAcrossThreads() throws Exception {
        RuntimeContext ctx = DoqaContexts.open("uid-async");
        try {
            Doqa.Context handle = Doqa.captureContext();
            Thread worker = new Thread(() -> Doqa.runWith(handle, () -> Doqa.step("from worker")));
            worker.start();
            worker.join();
            assertEquals(1, ctx.callSteps.size(), "step from the spawned thread recorded");
            assertEquals("from worker", ctx.callSteps.get(0).title);
        } finally {
            DoqaContexts.remove("uid-async");
        }
    }

    // ------------------------------------------------------------------ builder
    @Test
    void gateSkipsBuildAndUploads() {
        RuntimeContext ctx = new RuntimeContext("uid-gate");
        ctx.testRef = new TestRef("com.x.T", "m", "", "m()", false, null, null);
        ctx.attachments.add(AttachmentRef.ofBytes("a.txt", new byte[]{1}, null));
        int[] uploads = {0};
        ResultBuilder.Built built = ResultBuilder.build(ctx, "passed", null, null,
                ref -> { uploads[0]++; return "MF"; }, id -> false, null);
        assertNull(built, "gated-out test builds nothing");
        assertEquals(0, uploads[0], "no attachment upload for an out-of-scope test");
    }

    @Test
    void runtimeExternalIdOverrideWins() {
        RuntimeContext ctx = new RuntimeContext("uid-override");
        ctx.testRef = new TestRef("com.x.T", "m", "", "m()", false, null, null);
        ctx.externalId = "PINNED-1";
        ResultBuilder.Built built = ResultBuilder.build(ctx, "passed", null, null,
                ref -> null, null, null);
        assertEquals("PINNED-1", built.def.externalId());
        assertEquals("PINNED-1", built.result.externalId());
    }

    @DoqaCreateManualCase
    static class ClassOptIn {
        void inheritedFlag() {
        }
    }

    static class InheritedClassOptIn extends ClassOptIn {
        @Override
        void inheritedFlag() {
        }
    }

    @Test
    void createManualCaseAnnotationReachesDirectResultOnly() throws Exception {
        RuntimeContext ctx = new RuntimeContext("uid-create-case");
        ctx.testRef = ref("explicit", "explicit", false);
        ResultBuilder.Built built = ResultBuilder.build(
                ctx, "passed", null, null, attachment -> null, null, null);

        assertEquals(true, built.result.toPayload().get("create_manual_case"));
        assertNull(built.def.toPayload().get("create_manual_case"));
    }

    @Test
    void allureIdTravelsWithTheDefinition() throws Exception {
        RuntimeContext ctx = new RuntimeContext("uid-allure-id");
        ctx.testRef = ref("allureAnnotated", "n", false);
        ResultBuilder.Built built = ResultBuilder.build(
                ctx, "passed", null, null, attachment -> null, null, null);

        assertEquals("777", built.def.toPayload().get("allure_id"));
        assertEquals("777", built.allureId);

        RuntimeContext plain = new RuntimeContext("uid-no-allure-id");
        plain.testRef = ref("explicit", "explicit", false);
        ResultBuilder.Built bare = ResultBuilder.build(
                plain, "passed", null, null, attachment -> null, null, null);
        assertNull(bare.def.toPayload().get("allure_id"));
    }

    @Test
    void classLevelManualCaseFlagIsInherited() throws Exception {
        Method method = InheritedClassOptIn.class.getDeclaredMethod("inheritedFlag");
        TestRef inherited = new TestRef(
                InheritedClassOptIn.class.getName(), method.getName(), "", method.getName(),
                false, InheritedClassOptIn.class, method);

        Meta meta = MetaReader.read(inherited);

        assertTrue(meta.createManualCase);
    }

    @Test
    void runtimeManualCaseOptInReachesResultAndAllureLabel(@TempDir Path dir) throws Exception {
        RuntimeContext ctx = DoqaContexts.open("uid-create-case-runtime");
        try {
            ctx.testRef = ref("bare", "bare()", false);   // no marker annotation on this method
            Doqa.addCreateManualCase();
            ResultBuilder.Built built = ResultBuilder.build(
                    ctx, "passed", null, null, attachment -> null, null, null);

            assertEquals(true, built.result.toPayload().get("create_manual_case"));

            new AllureFileWriter(dir, "junit-platform")
                    .write(built.def, built.result, built.fullName, built.allureId);
            assertEquals("true", label(dir, AllureFileWriter.LABEL_CREATE_MANUAL_CASE));
        } finally {
            DoqaContexts.remove("uid-create-case-runtime");
        }
    }

    @Test
    void runtimeManualCaseOptInIsNoOpOutsideTest() {
        assertNull(DoqaContexts.current(), "no context bound");
        assertDoesNotThrow(Doqa::addCreateManualCase);
    }

    /** Value of the Allure label {@code name} in the single result file written to {@code dir}. */
    @SuppressWarnings("unchecked")
    private static String label(Path dir, String name) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            Path file = files.filter(p -> p.getFileName().toString().endsWith("-result.json"))
                    .findFirst().orElseThrow();
            Map<String, Object> result = Json.parseObject(new String(Files.readAllBytes(file)));
            for (Object entry : (List<Object>) result.get("labels")) {
                Map<String, Object> label = (Map<String, Object>) entry;
                if (name.equals(label.get("name"))) {
                    return String.valueOf(label.get("value"));
                }
            }
            return null;
        }
    }

    @Test
    void tracesAndMessagesAreTruncatedWithMarker() {
        RuntimeContext ctx = new RuntimeContext("uid-trunc");
        ctx.testRef = new TestRef("com.x.T", "m", "", "m()", false, null, null);
        String hugeTrace = "x".repeat(DoqaConfigDefaults.TRACE + 5_000);
        ResultBuilder.Built built = ResultBuilder.build(ctx, "failed", "msg", hugeTrace,
                ref -> null, null, null);
        Map<String, Object> payload = built.result.toPayload();
        String traces = String.valueOf(payload.get("traces"));
        assertTrue(traces.length() < hugeTrace.length());
        assertTrue(traces.endsWith("truncated (5000 chars)"), traces.substring(traces.length() - 40));
    }

    /** Local mirror of the client defaults used in assertions. */
    private static final class DoqaConfigDefaults {
        static final int TRACE = app.doqa.client.DoqaConfig.DEFAULT_MAX_TRACE_LENGTH;
    }

    // ------------------------------------------------------------------ placeholders
    @Test
    void templateRegexMatchesSubstitutedIds() {
        java.util.regex.Pattern p = Placeholders.templateToRegex("login_{browser}");
        assertTrue(p.matcher("login_chrome").matches());
        assertTrue(!p.matcher("logout_chrome").matches());
        List<String> resolved = Placeholders.resolveAll(List.of("id-{v}"), Map.of("v", "7"));
        assertEquals(List.of("id-7"), resolved);
    }
}
