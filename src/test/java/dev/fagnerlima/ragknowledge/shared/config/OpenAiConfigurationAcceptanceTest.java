package dev.fagnerlima.ragknowledge.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;

import dev.fagnerlima.ragknowledge.RagKnowledgeApiApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the OpenAI configuration as the operator sees it: the environment variables documented in the
 * README, resolved through application.properties. Variables are passed as command-line arguments, which take
 * precedence over the real environment, so the documented OPENAI_* variables exported in the developer's shell
 * cannot change the outcome. The base URL points to a closed local port so nothing can reach OpenAI.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiConfigurationAcceptanceTest {

    private static final String SYNTHETIC_KEY = "sk-qa-synthetic-key-not-real-0123456789";

    @Test
    void ac1_applicationPropertiesUsesNoDeprecatedSpringAiProperty() throws IOException {
        Set<String> deprecated = deprecatedPropertiesFromClasspathMetadata();
        Properties applicationProperties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            applicationProperties.load(in);
        }

        // Proves the metadata was really read, so an empty set cannot make the check pass vacuously.
        assertThat(deprecated).contains(
                "spring.ai.openai.chat.options.model",
                "spring.ai.openai.chat.options.max-completion-tokens",
                "spring.ai.openai.chat.options.reasoning-effort");
        assertThat(applicationProperties.stringPropertyNames()).doesNotContainAnyElementsOf(deprecated);
    }

    @Test
    void ac1_environmentOverridesReachTheChatModelThroughTheNewPropertyNames() {
        try (ConfigurableApplicationContext context = start(
                "--OPENAI_API_KEY=" + SYNTHETIC_KEY,
                "--OPENAI_CHAT_MODEL=gpt-qa-override-model",
                "--OPENAI_MAX_COMPLETION_TOKENS=1234",
                "--OPENAI_REASONING_EFFORT=medium")) {
            var options = context.getBean(OpenAiChatModel.class).getOptions();

            assertThat(options.getModel()).isEqualTo("gpt-qa-override-model");
            assertThat(options.getMaxCompletionTokens()).isEqualTo(1234);
            assertThat(options.getReasoningEffort()).isEqualTo("medium");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void ac2_startupFailsNamingTheVariableWhenOpenAiApiKeyIsEmptyOrBlank(String apiKey) {
        Throwable failure = startCatching("--OPENAI_API_KEY=" + apiKey);

        assertThat(failure).isNotNull();
        assertThat(rootCause(failure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void ac2_startupFailsNamingTheVariableWhenNoKeyIsConfiguredAtAll() {
        // Without OPENAI_API_KEY in any property source the placeholder default applies; run with
        // `env -u OPENAI_API_KEY` for this to model an unset variable (the command-line form above covers the
        // empty value regardless of the shell).
        String fromEnvironment = System.getenv("OPENAI_API_KEY");
        assumeTrue(fromEnvironment == null,
                "OPENAI_API_KEY is exported in this shell; the unset case cannot be modelled");

        Throwable failure = startCatching();

        assertThat(failure).isNotNull();
        assertThat(rootCause(failure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void ac2_ac3_startupWithAKeyNeverWritesTheKeyValueToTheOutput(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start("--OPENAI_API_KEY=" + SYNTHETIC_KEY)) {
            assertThat(context.isActive()).isTrue();
        }

        assertThat(output.getAll()).doesNotContain(SYNTHETIC_KEY);
    }

    @Test
    void ac3_startupWithAKeyKeepsTheDocumentedChatDefaults() {
        // Defaults apply only when the documented overrides are not exported (direnv may export them).
        assumeTrue(System.getenv("OPENAI_CHAT_MODEL") == null
                        && System.getenv("OPENAI_MAX_COMPLETION_TOKENS") == null
                        && System.getenv("OPENAI_REASONING_EFFORT") == null,
                "a chat override is exported in this shell; the defaults cannot be observed");

        try (ConfigurableApplicationContext context = start("--OPENAI_API_KEY=" + SYNTHETIC_KEY)) {
            var options = context.getBean(OpenAiChatModel.class).getOptions();

            assertThat(options.getModel()).isEqualTo("gpt-5-mini");
            assertThat(options.getMaxCompletionTokens()).isEqualTo(3000);
            assertThat(options.getReasoningEffort()).isEqualTo("low");
        }
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(RagKnowledgeApiApplication.class)
                .properties("server.port=0", "spring.ai.openai.base-url=http://127.0.0.1:9")
                .run(args);
    }

    private static Throwable startCatching(String... args) {
        try (ConfigurableApplicationContext ignored = start(args)) {
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Set<String> deprecatedPropertiesFromClasspathMetadata() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        Set<String> deprecated = new HashSet<>();
        for (URL url : Collections.list(OpenAiConfigurationAcceptanceTest.class.getClassLoader()
                .getResources("META-INF/spring-configuration-metadata.json"))) {
            try (InputStream in = url.openStream()) {
                for (JsonNode property : mapper.readTree(in).path("properties")) {
                    if (property.path("deprecated").asBoolean(false) || property.has("deprecation")) {
                        deprecated.add(property.path("name").asString());
                    }
                }
            }
        }
        return deprecated;
    }
}
