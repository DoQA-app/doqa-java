package app.doqa.junit5;

import app.doqa.e2e.regression.ClassAllureScenario;
import app.doqa.e2e.regression.ClassIdScenario;
import app.doqa.e2e.regression.KeysScenario;
import app.doqa.testkit.FakeBackend;
import app.doqa.testkit.PayloadSnapshot;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class KeyRegressionTest {

    @AfterEach
    void cleanup() {
        FakeBackend.uninstall();
    }

    @Test
    void apiPayloadMatchesGolden(@TempDir Path dir) {
        FakeBackend backend = new FakeBackend().install(dir);
        launch();
        PayloadSnapshot.assertGolden("junit5-api", PayloadSnapshot.api(backend));
    }

    @Test
    void fileSinkMatchesGolden(@TempDir Path dir) {
        FakeBackend.installFiles(dir);
        launch();
        PayloadSnapshot.assertGolden("junit5-files", PayloadSnapshot.files(dir));
    }

    private static void launch() {
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .selectors(DiscoverySelectors.selectClass(KeysScenario.class),
                        DiscoverySelectors.selectClass(ClassIdScenario.class),
                        DiscoverySelectors.selectClass(ClassAllureScenario.class))
                .build());
    }
}
