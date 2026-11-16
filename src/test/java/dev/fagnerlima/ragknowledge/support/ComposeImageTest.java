package dev.fagnerlima.ragknowledge.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Tests run against the database the app runs on: compose.yaml and the test container use the same image. */
class ComposeImageTest {

    @Test
    void composeUsesTheSamePinnedImageAsTheTestContainer() throws IOException {
        Map<String, Object> compose;

        // Maven sets basedir for Surefire; an IDE usually runs from the project directory instead.
        Path projectDirectory = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));

        try (Reader reader = Files.newBufferedReader(projectDirectory.resolve("compose.yaml"))) {
            compose = new Yaml().load(reader);
        }

        assertThat(compose)
                .extractingByKey("services").asInstanceOf(MAP)
                .extractingByKey("postgres").asInstanceOf(MAP)
                .containsEntry("image", PgvectorTestDatabase.IMAGE);
    }
}
