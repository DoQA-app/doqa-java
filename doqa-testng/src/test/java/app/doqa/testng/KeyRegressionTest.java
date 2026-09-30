package app.doqa.testng;

import app.doqa.e2eng.regression.ClassAllureScenario;
import app.doqa.e2eng.regression.ClassIdScenario;
import app.doqa.e2eng.regression.KeysScenario;
import app.doqa.testkit.FakeBackend;
import app.doqa.testkit.PayloadSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.TestNG;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

public class KeyRegressionTest {

    @AfterMethod(alwaysRun = true)
    public void cleanup() {
        FakeBackend.uninstall();
        DoqaTestNgListener.resetState();
    }

    @Test
    public void apiPayloadMatchesGolden() throws IOException {
        FakeBackend backend = new FakeBackend().install(Files.createTempDirectory("doqa-regression"));
        run();
        PayloadSnapshot.assertGolden("testng-api", PayloadSnapshot.api(backend));
    }

    @Test
    public void fileSinkMatchesGolden() throws IOException {
        Path dir = Files.createTempDirectory("doqa-regression");
        FakeBackend.installFiles(dir);
        run();
        PayloadSnapshot.assertGolden("testng-files", PayloadSnapshot.files(dir));
    }

    private static void run() {
        DoqaTestNgListener.resetState();
        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setOutputDirectory("target/testng-out");
        testng.setTestClasses(new Class<?>[]{KeysScenario.class, ClassIdScenario.class,
                ClassAllureScenario.class});
        testng.run();
    }
}
