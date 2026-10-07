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

    @Test
    void classpathUriIsDecoded() {
        assertEquals("features/my dir/a.feature", FeatureSources.path("classpath:features/my%20dir/a.feature"));
        assertEquals("features/каталог/a.feature",
                FeatureSources.path("classpath:features/%D0%BA%D0%B0%D1%82%D0%B0%D0%BB%D0%BE%D0%B3/a.feature"));
        assertEquals("features/каталог/a.feature", FeatureSources.path("classpath:features/каталог/a.feature"));
        assertEquals("features/my dir/a.feature", FeatureSources.path("features/my%20dir/a.feature"));
    }

    @Test
    void plusStaysLiteralAndEncodedPlusIsDecoded() {
        assertEquals("features/c++/a.feature", FeatureSources.path("classpath:features/c++/a.feature"));
        assertEquals("features/c++/a.feature", FeatureSources.path("classpath:features/c%2B%2B/a.feature"));
        assertEquals("features/c++/a.feature", FeatureSources.path("jar:file:/tmp/x.jar!/features/c%2B+/a.feature"));
    }

    @Test
    void jarUriIsDecoded() {
        assertEquals("features/my dir/каталог/a.feature", FeatureSources.path(
                "jar:file:/tmp/my%20jars/x.jar!/features/my%20dir/%D0%BA%D0%B0%D1%82%D0%B0%D0%BB%D0%BE%D0%B3/a.feature"));
    }

    @Test
    void classpathFileAndJarUrisOfASpacedPathAgree() {
        String expected = "features/with space/каталог фич/spaced.feature";
        Path copy = Paths.get("target/test-classes", expected).toAbsolutePath();
        Path source = Paths.get("src/test/resources", expected).toAbsolutePath();
        assertEquals(expected, FeatureSources.path(copy.toUri().toString()));
        assertEquals(expected, FeatureSources.path(source.toUri().toString()));
        assertEquals(expected, FeatureSources.path(copy.toUri().toASCIIString()));
        assertEquals(expected, FeatureSources.path(
                "classpath:features/with%20space/%D0%BA%D0%B0%D1%82%D0%B0%D0%BB%D0%BE%D0%B3%20%D1%84%D0%B8%D1%87/spaced.feature"));
        assertEquals(expected, FeatureSources.path("classpath:features/with%20space/каталог%20фич/spaced.feature"));
        assertEquals(expected, FeatureSources.path("jar:" + source.getParent().toUri() + "x.jar!/"
                + "features/with%20space/%D0%BA%D0%B0%D1%82%D0%B0%D0%BB%D0%BE%D0%B3%20%D1%84%D0%B8%D1%87/spaced.feature"));
    }

    @Test
    void encodedClasspathFeatureIsReadable() {
        assertEquals("Spaced path",
                FeatureSources.file("classpath:features/with%20space/каталог%20фич/spaced.feature").nameAt(1));
    }

    @Test
    void pathsWithoutSpecialCharactersAreUnchanged() {
        assertEquals("features/bank/transfer.feature", FeatureSources.decode("features/bank/transfer.feature"));
        assertEquals("features/a-b_c.d/x.feature", FeatureSources.path("classpath:features/a-b_c.d/x.feature"));
        assertEquals("features/a/x.feature", FeatureSources.path("jar:file:/tmp/x.jar!/features/a/x.feature"));
    }

    @Test
    void malformedEscapesAreKept() {
        assertEquals("a%zz/b%2", FeatureSources.decode("a%zz/b%2"));
        assertEquals("100% done", FeatureSources.decode("100% done"));
    }
}
