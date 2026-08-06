package app.doqa.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract fixtures for the file sink: reporting/resultsDir config resolution and the projection
 * of the rich def/result model into parser-compatible Allure files (DoQA labels, step node
 * shape, fixtures container, attachment copy+meta).
 */
class AllureFileWriterTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ config
    @Test
    void reportingAndResultsDirResolveWithAliasesAndDefaults() {
        Properties props = new Properties();
        props.setProperty("doqa.reporting", "files");
        props.setProperty("doqa.results-dir", "build/doqa");
        DoqaConfig cfg = ConfigResolver.resolve(props, Map.of(), null);
        assertEquals("files", cfg.effectiveReporting());
        assertEquals("build/doqa", cfg.resultsDir());

        DoqaConfig defaults = ConfigResolver.resolve(new Properties(), Map.of(), null);
        assertEquals("auto", defaults.reporting());
        assertEquals("results", defaults.resultsDir());
        // auto without url/token/space -> files; with full API config -> api
        assertEquals("files", defaults.effectiveReporting());
        DoqaConfig api = new DoqaConfig.Builder().url("http://x").token("t").spaceId("1").build();
        assertEquals("api", api.effectiveReporting());
        DoqaConfig off = new DoqaConfig.Builder().reporting("off").build();
        assertEquals("off", off.effectiveReporting());
    }

    // ------------------------------------------------------------------ files
    @Test
    void writesParserCompatibleResultContainerAndAttachment() throws IOException {
        AllureFileWriter writer = new AllureFileWriter(tmp, "junit-platform");

        Path shot = Files.write(tmp.resolve("screen.png"), new byte[]{1, 2, 3});
        String source = writer.storeAttachment(shot.toString());
        assertTrue(Files.exists(tmp.resolve(source)), "attachment copied");

        StepResult inner = new StepResult("inner", "passed", 5L, null, null, null)
                .startedOn(1005L).completedOn(1010L);
        StepResult outer = new StepResult("outer", "failed", 20L, "step boom",
                List.of(new Attachment(source)), List.of(inner))
                .startedOn(1000L).completedOn(1020L);
        StepResult setup = new StepResult("setUp", "passed", 2L, null, null, null);
        StepResult teardown = new StepResult("tearDown", "passed", 1L, null, null, null);

        AutotestDef def = new AutotestDef("DOQA-7", "login test")
                .title("Login with valid credentials").description("Verifies happy-path login")
                .namespace("io.acme").classname("LoginTest")
                .tags(List.of("ui")).labels(List.of("smoke"))
                .caseIds(List.of(11L, 12L));
        AutotestResult result = new AutotestResult("DOQA-7", Outcome.FAILED)
                .name("login test").startedOn(900L).completedOn(1100L).durationMs(200L)
                .message("boom").traces("stack...")
                .createManualCase(true)
                .stepResults(List.of(outer))
                .setupResults(List.of(setup))
                .teardownResults(List.of(teardown))
                .parameters(List.of(new Parameter("browser", "chrome")));

        writer.write(def, result, "io.acme.LoginTest.login", "77");

        Map<String, Object> res = readSingle("-result.json");
        assertEquals("DOQA-7", res.get("historyId"));
        assertEquals("io.acme.LoginTest.login", res.get("fullName"));
        assertEquals("failed", res.get("status"));
        assertEquals(900L, res.get("start"));
        assertEquals(1100L, res.get("stop"));
        assertEquals("boom", ((Map<?, ?>) res.get("statusDetails")).get("message"));
        assertEquals("Verifies happy-path login", res.get("description"));

        Map<String, String> labels = labelMap(res.get("labels"));
        assertEquals("DOQA-7", labels.get("doqa_id"));
        assertEquals("11,12", labels.get("doqa_cases"));
        assertEquals("77", labels.get("AS_ID"));
        assertEquals("io.acme", labels.get("package"));
        assertEquals("LoginTest", labels.get("testClass"));
        assertEquals("Login with valid credentials", labels.get("doqa_title"));
        assertEquals("true", labels.get("doqa_create_manual_case"));

        // steps: nested Allure node shape (name/status/start/stop/steps)
        List<?> steps = (List<?>) res.get("steps");
        Map<?, ?> outerNode = (Map<?, ?>) steps.get(0);
        assertEquals("outer", outerNode.get("name"));
        assertEquals("failed", outerNode.get("status"));
        assertEquals(1000L, outerNode.get("start"));
        assertEquals(1020L, outerNode.get("stop"));
        assertEquals("step boom", ((Map<?, ?>) outerNode.get("statusDetails")).get("message"));
        Map<?, ?> innerNode = (Map<?, ?>) ((List<?>) outerNode.get("steps")).get(0);
        assertEquals("inner", innerNode.get("name"));

        // attachment resolved to {name, source, type}
        Map<?, ?> att = (Map<?, ?>) ((List<?>) outerNode.get("attachments")).get(0);
        assertEquals("screen.png", att.get("name"));
        assertEquals(source, att.get("source"));
        assertEquals("image/png", att.get("type"));

        // container carries fixtures and points to the result uuid
        Map<String, Object> container = readSingle("-container.json");
        assertEquals(List.of(res.get("uuid")), container.get("children"));
        assertEquals("setUp", ((Map<?, ?>) ((List<?>) container.get("befores")).get(0)).get("name"));
        assertEquals("tearDown", ((Map<?, ?>) ((List<?>) container.get("afters")).get(0)).get("name"));
    }

    @Test
    void noContainerWithoutFixtures() throws IOException {
        AllureFileWriter writer = new AllureFileWriter(tmp, "junit-platform");
        writer.write(new AutotestDef("X-1", "t"),
                new AutotestResult("X-1", Outcome.PASSED).name("t"), null, null);
        assertEquals(1, list("-result.json").size());
        assertTrue(list("-container.json").isEmpty());
        Map<String, Object> res = readSingle("-result.json");
        assertNull(res.get("fullName"));
        assertNotNull(res.get("uuid"));
        assertNull(res.get("description"), "no description → key absent, not blank");
        Map<String, String> labels = labelMap(res.get("labels"));
        assertNull(labels.get("doqa_title"), "no title → no doqa_title label");
        assertNull(labels.get("package"), "no namespace → no package label");
        assertNull(labels.get("testClass"), "no classname → no testClass label");
    }

    @Test
    void storesInMemoryAttachmentWithExplicitType() throws IOException {
        AllureFileWriter writer = new AllureFileWriter(tmp, "junit-platform");
        String source = writer.storeAttachment("api-log.txt", "hello".getBytes(), null);
        assertTrue(Files.exists(tmp.resolve(source)));
        assertEquals("hello", Files.readString(tmp.resolve(source)));

        AutotestResult result = new AutotestResult("X-2", Outcome.PASSED).name("t")
                .attachments(List.of(new Attachment(source)));
        writer.write(new AutotestDef("X-2", "t"), result, null, null);
        Map<String, Object> res = readSingle("-result.json");
        Map<?, ?> att = (Map<?, ?>) ((List<?>) res.get("attachments")).get(0);
        assertEquals("api-log.txt", att.get("name"));
        assertEquals("text/plain", att.get("type"), "type inferred from the name");
    }

    @Test
    void writesEnvironmentProperties() throws IOException {
        AllureFileWriter writer = new AllureFileWriter(tmp, "junit-platform");
        writer.writeEnvironment("staging");
        assertEquals("environment=staging\n",
                Files.readString(tmp.resolve("environment.properties")));
        // blank environment -> no file
        AllureFileWriter other = new AllureFileWriter(tmp.resolve("empty"), "junit-platform");
        other.writeEnvironment("  ");
        assertTrue(!Files.exists(tmp.resolve("empty").resolve("environment.properties")));
    }

    // ------------------------------------------------------------------ helpers
    private List<Path> list(String suffix) throws IOException {
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .collect(Collectors.toList());
        }
    }

    private Map<String, Object> readSingle(String suffix) throws IOException {
        List<Path> hits = list(suffix);
        assertEquals(1, hits.size(), suffix + " count");
        return Json.parseObject(new String(Files.readAllBytes(hits.get(0))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labelMap(Object labels) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Object l : (List<Object>) labels) {
            Map<String, Object> m = (Map<String, Object>) l;
            out.putIfAbsent(String.valueOf(m.get("name")), String.valueOf(m.get("value")));
        }
        return out;
    }
}
