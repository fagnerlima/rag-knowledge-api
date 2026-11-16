package dev.fagnerlima.ragknowledge.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL with pgvector for full-context tests: {@code @Import} it to connect the context to one container shared
 * by the whole test JVM (started on first use, removed by Testcontainers when the JVM exits) and to replace the
 * OpenAI embedding model with {@link FakeEmbeddingModel}, so no test ever calls the embeddings API.
 *
 * <p>The container is deliberately not a bean (nor {@code @ServiceConnection}): Spring Boot closes container beans
 * when their context closes, even with {@code destroyMethod = ""}, so {@code @DirtiesContext} or a context cache
 * eviction would stop the container every other context uses. Only its connection properties are published.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PgvectorTestDatabase {

    static final String IMAGE_REPOSITORY = "pgvector/pgvector";
    static final String IMAGE_TAG = "0.8.7-pg17";
    static final String IMAGE_DIGEST = "sha256:b513c48566e4777cd3370451400000c78eda6a08131d68f4c31d4a76f27bd8fd";

    /** The image reference written in compose.yaml; ComposeImageTest keeps both in sync. */
    public static final String IMAGE = IMAGE_REPOSITORY + ":" + IMAGE_TAG + "@" + IMAGE_DIGEST;

    @Bean
    DynamicPropertyRegistrar pgvectorDatasourceProperties() {
        return registry -> {
            PostgreSQLContainer container = SharedContainer.INSTANCE;

            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        };
    }

    @Bean
    @Primary
    FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }

    /** For contexts started without the test framework (SpringApplicationBuilder): command-line datasource args. */
    public static String[] datasourceArguments() {
        PostgreSQLContainer container = SharedContainer.INSTANCE;

        return new String[] {
                "--spring.datasource.url=" + container.getJdbcUrl(),
                "--spring.datasource.username=" + container.getUsername(),
                "--spring.datasource.password=" + container.getPassword()
        };
    }

    static String containerId() {
        return SharedContainer.INSTANCE.getContainerId();
    }

    /** Holder idiom: the container starts only when a test needs it, never when the constants are read. */
    private static final class SharedContainer {

        // Testcontainers cannot parse a reference with both tag and digest; the digest alone pins the same image.
        static final PostgreSQLContainer INSTANCE = start(
                new PostgreSQLContainer(DockerImageName.parse(IMAGE_REPOSITORY + "@" + IMAGE_DIGEST)));

        private static PostgreSQLContainer start(PostgreSQLContainer container) {
            container.start();

            return container;
        }
    }
}
