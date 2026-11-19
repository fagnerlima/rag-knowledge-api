package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class RetrievalPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsValuesWithinTheBounds() {
        runner.withPropertyValues("rag.retrieval.top-k=20", "rag.retrieval.similarity-threshold=1.0")
                .run(context -> assertThat(context.getBean(RetrievalProperties.class))
                        .isEqualTo(new RetrievalProperties(20, 1.0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rag.retrieval.top-k=0",
            "rag.retrieval.top-k=21",
            "rag.retrieval.similarity-threshold=-0.1",
            "rag.retrieval.similarity-threshold=1.5"
    })
    void rejectsValuesOutsideTheBoundsAtStartup(String invalidValue) {
        runner.withPropertyValues("rag.retrieval.top-k=4", "rag.retrieval.similarity-threshold=0.25", invalidValue)
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(RetrievalProperties.class)
    static class PropertiesConfiguration {
    }
}
