package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class DoqaTagsTest {

    private static DoqaTags resolve(String name, List<String> feature, List<String> rule,
                                    List<String> scenario, List<String> examples) {
        return DoqaTags.resolve(Arrays.asList(feature, rule, scenario, examples), 2, name,
                "t.feature:" + name.hashCode());
    }

    @Test
    void narrowestLevelWinsPerBranch() {
        DoqaTags tags = resolve("s",
                List.of("doqa.id:F", "allure.id:1", "DOQA-1"),
                List.of("doqa.id:R"),
                List.of("doqa.id:S", "DOQA:2"),
                List.of("doqa.id:E"));
        assertEquals("E", tags.explicitId);
        assertEquals("DOQA-2", tags.titleId);
        assertEquals("1", tags.allureId);
    }

    @Test
    void idInTheScenarioNameBelongsToTheScenarioLevel() {
        DoqaTags tags = resolve("[DOQA-7] Login", List.of("DOQA-1"), List.of(), List.of(), List.of());
        assertEquals("DOQA-7", tags.titleId);
        assertEquals("DOQA-9", resolve("[DOQA-7] Login", List.of(), List.of(), List.of(),
                List.of("DOQA-9")).titleId);
    }

    @Test
    void twoValuesOnOneLevelWarnAndTakeTheFirst() {
        List<String> warnings = new ArrayList<>();
        Handler handler = capture(warnings);
        try {
            DoqaTags tags = resolve("two ids", List.of(), List.of(),
                    List.of("doqa.id:first", "doqa.id:second"), List.of());
            assertEquals("first", tags.explicitId);
        } finally {
            Logger.getLogger(DoqaTags.class.getName()).removeHandler(handler);
        }
        assertTrue(warnings.stream().anyMatch(w -> w.contains("first")), warnings.toString());
    }

    @Test
    void equalsSignAndAnyCaseOfThePrefixAreAccepted() {
        DoqaTags tags = resolve("s", List.of("Doqa.Case=3"), List.of(),
                List.of("DOQA.ID=abc", "allure.id=42", "DOQA=5"), List.of());
        assertEquals("abc", tags.explicitId);
        assertEquals("42", tags.allureId);
        assertEquals("DOQA-5", tags.titleId);
        assertArrayEquals(new long[] {3}, tags.caseIds);
    }

    @Test
    void casesAreJoinedAcrossLevelsAndBadOnesIgnored() {
        DoqaTags tags = resolve("s", List.of("doqa.case:1"), List.of("doqa.case:2"),
                List.of("doqa.case:1", "doqa.case:x", "doqa.case:"), List.of("doqa.case:4"));
        assertArrayEquals(new long[] {1, 2, 4}, tags.caseIds);
    }

    @Test
    void serviceTagsDoNotReachTheAutotestTags() {
        DoqaTags tags = resolve("s", List.of("smoke", "doqa.case:1"), List.of("rule"),
                List.of("DOQA-3", "doqa.id:x", "allure.id:4", "smoke", "api:v2"), List.of());
        assertEquals(Arrays.asList("smoke", "rule", "api:v2"), tags.tags);
        assertNull(resolve("s", List.of(), List.of(), List.of(), List.of()).caseIds);
    }

    @Test
    void serviceTagRecognition() {
        assertTrue(DoqaTags.isServiceTag("doqa.id:1"));
        assertTrue(DoqaTags.isServiceTag("DOQA-12"));
        assertTrue(DoqaTags.isServiceTag("Allure.Id=3"));
        assertFalse(DoqaTags.isServiceTag("doqa.other:1"));
        assertFalse(DoqaTags.isServiceTag("DOQA-x"));
        assertFalse(DoqaTags.isServiceTag("smoke"));
    }

    private static Handler capture(List<String> into) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                into.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger(DoqaTags.class.getName()).addHandler(handler);
        return handler;
    }
}
