package app.doqa.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.doqa.client.DoqaConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * Reporting of an unconfigured run: the {@code auto} fallback to the file sink is warned about and
 * names the missing settings, while a deliberate {@code files}/configured setup stays quiet.
 */
class UnconfiguredWarningTest {

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
    }

    private List<String> warnings() {
        List<String> out = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                out.add(record.getMessage());
            }
        }
        return out;
    }

    @Test
    void missingConfigurationIsReportedWithTheMissingKeys(@TempDir Path dir) {
        DoqaSession.setConfigOverride(new DoqaConfig.Builder()
                .resultsDir(dir.toString())
                .build());

        DoqaSession session = DoqaSession.getOrInit();

        assertTrue(session.enabled);
        assertTrue(session.fileSink());

        List<String> warnings = warnings();
        assertEquals(1, warnings.size(), "expected exactly one warning, got: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.contains("(missing url, token, spaceId)"), warning);
        assertTrue(warning.contains("NOT sent to DoQA"), warning);
        assertTrue(warning.contains(dir.toString()), warning);
    }

    @Test
    void partialConfigurationNamesOnlyWhatIsMissing(@TempDir Path dir) {
        DoqaSession.setConfigOverride(new DoqaConfig.Builder()
                .url("https://doqa.example")
                .spaceId("3")
                .resultsDir(dir.toString())
                .build());

        DoqaSession.getOrInit();

        String warning = warnings().get(0);
        assertTrue(warning.contains("(missing token)"), warning);
    }

    @Test
    void explicitFileReportingStaysQuiet(@TempDir Path dir) {
        DoqaSession.setConfigOverride(new DoqaConfig.Builder()
                .reporting(DoqaConfig.REPORTING_FILES)
                .resultsDir(dir.toString())
                .build());

        DoqaSession.getOrInit();

        assertTrue(warnings().isEmpty(), "explicit reporting=files is a deliberate choice: " + warnings());
    }

    @Test
    void explicitApiReportingWithoutCredentialsIsReported(@TempDir Path dir) {
        DoqaSession.setConfigOverride(new DoqaConfig.Builder()
                .reporting(DoqaConfig.REPORTING_API)
                .url("https://doqa.example")
                .resultsDir(dir.toString())
                .build());

        DoqaSession session = DoqaSession.getOrInit();

        assertFalse(session.enabled);
        String warning = warnings().get(0);
        assertTrue(warning.contains("(missing token, spaceId)"), warning);
        assertTrue(warning.contains("reporting is disabled"), warning);
    }

    @Test
    void configuredRunDoesNotWarn(@TempDir Path dir) {
        DoqaSession.setConfigOverride(new DoqaConfig.Builder()
                .reporting(DoqaConfig.REPORTING_FILES)
                .url("https://doqa.example")
                .token("t")
                .spaceId("3")
                .resultsDir(dir.toString())
                .build());

        DoqaSession.getOrInit();

        assertTrue(warnings().isEmpty(), "fully configured run must be quiet: " + warnings());
    }
}
