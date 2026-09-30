package app.doqa.junit4;

import app.doqa.e2e4.regression.ClassAllureScenario;
import app.doqa.e2e4.regression.ClassIdScenario;
import app.doqa.e2e4.regression.KeysScenario;
import app.doqa.e2e4.regression.ParamKeysScenario;
import app.doqa.e2e4.regression.PlainParamScenario;
import app.doqa.testkit.FakeBackend;
import app.doqa.testkit.PayloadSnapshot;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.JUnitCore;

public class KeyRegressionTest {

    @Rule
    public TemporaryFolder dir = new TemporaryFolder();

    @After
    public void cleanup() {
        FakeBackend.uninstall();
        AdapterState.resetAll();
    }

    @Test
    public void apiPayloadMatchesGolden() {
        FakeBackend backend = new FakeBackend().install(dir.getRoot().toPath());
        run();
        PayloadSnapshot.assertGolden("junit4-api", PayloadSnapshot.api(backend));
    }

    @Test
    public void fileSinkMatchesGolden() {
        FakeBackend.installFiles(dir.getRoot().toPath());
        run();
        PayloadSnapshot.assertGolden("junit4-files", PayloadSnapshot.files(dir.getRoot().toPath()));
    }

    private static void run() {
        AdapterState.resetAll();
        JUnitCore core = new JUnitCore();
        core.addListener(new DoqaRunListener());
        core.run(KeysScenario.class, ParamKeysScenario.class, PlainParamScenario.class,
                ClassIdScenario.class, ClassAllureScenario.class);
    }
}
