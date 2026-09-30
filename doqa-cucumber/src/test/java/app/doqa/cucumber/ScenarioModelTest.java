package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.doqa.core.Attribution;
import app.doqa.core.TestRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioModelTest {

    private static final String URI = "classpath:features/bank/transfer.feature";
    private static final String PATH = "features/bank/transfer.feature";

    private final FeatureFile file = FeatureSources.file(URI);

    @Test
    void plainScenarioKeyIsTheHashOfPathAndName() throws Exception {
        ScenarioModel model = ScenarioModel.of(URI, file, 0, 8, 0, 0);
        assertEquals(PATH + "#Simple transfer", model.signature());
        assertEquals("cucumber:" + sha1(PATH + "#Simple transfer"), externalId(model));
        assertFalse(model.outline);
        assertEquals("features/bank", model.namespace());
        assertEquals("Money transfer", model.featureName);
        assertEquals("features/bank/transfer", model.featureKey());
        assertEquals(Arrays.asList("bank"), model.tags.tags);
    }

    @Test
    void scenarioInsideARuleHasTheRuleInItsSignature() throws Exception {
        ScenarioModel model = ScenarioModel.of(URI, file, 39, 42, 0, 0);
        assertEquals(PATH + "#Limits/Over the limit", model.signature());
        assertEquals("DOQA-501", externalId(model));
        ScenarioModel named = ScenarioModel.of(URI, file, 39, 46, 0, 0);
        assertEquals("DOQA-502", externalId(named));
    }

    @Test
    void outlineRowKeepsTheTemplateAndCarriesItsValues() throws Exception {
        ScenarioModel row = ScenarioModel.of(URI, file, 0, 13, 18, 21);
        assertTrue(row.outline);
        assertEquals("Transfer <amount> to <who>", row.name);
        assertEquals("Transfer 10 to alice", row.displayName);
        assertEquals("10", row.parameters.get("amount"));
        assertEquals("transfer-alice", externalId(row));
        assertEquals(Arrays.asList("bank", "outline", "first"), row.tags.tags);
        assertEquals(PATH + ":13", methodKey(row));

        ScenarioModel second = ScenarioModel.of(URI, file, 0, 13, 25, 27);
        assertEquals("eve|x", second.parameters.get("who"));
        assertEquals("Transfer 5 to eve|x", second.displayName);
        assertEquals("cucumber:" + sha1(PATH + "#Transfer <amount> to <who>"), externalId(second));
        assertEquals(PATH + ":13", methodKey(second));
    }

    @Test
    void casesComeFromEveryLevel() {
        ScenarioModel row = ScenarioModel.of(URI, file, 0, 13, 18, 21);
        TestRef ref = row.testRef();
        long[] cases = Attribution.resolve(ref).caseIds;
        assertEquals("[10, 11]", Arrays.toString(cases));
    }

    @Test
    void classpathAndFileUrisGiveOneKey() {
        String fileUri = Paths.get("src/test/resources/" + PATH).toAbsolutePath().toUri().toString();
        ScenarioModel viaFile = ScenarioModel.of(fileUri, FeatureSources.file(fileUri), 0, 8, 0, 0);
        ScenarioModel viaClasspath = ScenarioModel.of(URI, file, 0, 8, 0, 0);
        assertEquals(externalId(viaClasspath), externalId(viaFile));
    }

    @Test
    void featureAtTheClasspathRootHasNoNamespace() {
        FeatureSources.register("classpath:root.feature", "Feature: Root\n  Scenario: One\n");
        ScenarioModel model = ScenarioModel.of("classpath:root.feature",
                FeatureSources.file("classpath:root.feature"), 0, 2, 0, 0);
        assertNull(model.namespace());
        assertEquals("root.feature#One", model.signature());
    }

    @Test
    void interpolationReplacesEveryColumn() {
        assertEquals("a 1 b 2 <c>", ScenarioModel.interpolate("a <x> b <y> <c>",
                new java.util.LinkedHashMap<>(java.util.Map.of("x", "1", "y", "2"))));
        List<Object[]> pairs = ScenarioModel.of(URI, file, 0, 13, 18, 21).parameterPairs();
        assertEquals("amount", pairs.get(0)[0]);
        assertEquals("who", pairs.get(1)[0]);
        assertEquals("left", pairs.get(2)[0]);
    }

    private static String externalId(ScenarioModel model) {
        return Attribution.resolve(model.testRef()).externalId;
    }

    private static String methodKey(ScenarioModel model) {
        return model.testRef().methodKey();
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
