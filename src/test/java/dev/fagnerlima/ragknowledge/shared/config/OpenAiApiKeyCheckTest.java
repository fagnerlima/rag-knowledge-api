package dev.fagnerlima.ragknowledge.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// The wiring through application.properties and the startup are covered by OpenAiConfigurationAcceptanceTest.
class OpenAiApiKeyCheckTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsAMissingKeyWithAClearMessage(String apiKey) {
        assertThatThrownBy(() -> new OpenAiApiKeyCheck(apiKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(OpenAiApiKeyCheck.MISSING_KEY_MESSAGE);
    }

    @Test
    void acceptsAConfiguredKey() {
        assertThatCode(() -> new OpenAiApiKeyCheck("test-key-not-real")).doesNotThrowAnyException();
    }
}
