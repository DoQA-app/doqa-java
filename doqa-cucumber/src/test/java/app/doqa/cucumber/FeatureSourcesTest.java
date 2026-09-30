package app.doqa.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeatureSourcesTest {

    @AfterEach
    void reset() {
        FeatureSources.reset();
    }

    @Test
    void classpathUriLosesItsScheme() {
        assertEquals("features/bank/transfer.feature",
                FeatureSources.path("classpath:features/bank/transfer.feature"));
        assertEquals("features/bank/transfer.feature",
                FeatureSources.path("classpath:/features/bank/transfer.feature"));
    }

    @Test
    void fileUriOfTheSourceAndOfTheBuildCopyGiveTheClasspathPath() {
        Path source = Paths.get("src/test/resources/features/bank/transfer.feature").toAbsolutePath();
        Path copy = Paths.get("target/test-classes/features/bank/transfer.feature").toAbsolutePath();
        assertEquals("features/bank/transfer.feature", FeatureSources.path(source.toUri().toString()));
        assertEquals("features/bank/transfer.feature", FeatureSources.path(copy.toUri().toString()));
    }

    @Test
    void fileOutsideTheClasspathIsRelativeToTheWorkingDirectory() {
        Path pom = Paths.get("pom.xml").toAbsolutePath();
        assertEquals("pom.xml", FeatureSources.path(pom.toUri().toString()));
    }

    @Test
    void jarUriKeepsThePathInsideTheJar() {
        assertEquals("features/a.feature",
                FeatureSources.path("jar:file:/tmp/x.jar!/features/a.feature"));
    }

    @Test
    void fileIsReadFromTheClasspathOrTheDisk() {
        assertNotNull(FeatureSources.file("classpath:features/bank/transfer.feature"));
        Path source = Paths.get("src/test/resources/features/ru/transfer_ru.feature").toAbsolutePath();
        assertEquals("Перевод по-русски", FeatureSources.file(source.toUri().toString()).nameAt(3));
    }
}
