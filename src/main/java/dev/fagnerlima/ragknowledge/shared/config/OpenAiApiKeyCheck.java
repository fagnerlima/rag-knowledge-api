package dev.fagnerlima.ragknowledge.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Stops the startup when the OpenAI key is missing. Without it the application starts normally
 * and every answer fails later with a 401 from OpenAI, reported to the client only as a 502.
 *
 * <p>Only the common {@code spring.ai.openai.api-key} is supported, as documented. Switching to another
 * Spring AI credential (Microsoft Foundry, GitHub Models, a chat-specific key) means revisiting this check.
 * {@code @Lazy(false)} keeps the check running even with {@code spring.main.lazy-initialization=true}.
 */
@Component
@Lazy(false)
class OpenAiApiKeyCheck {

    static final String MISSING_KEY_MESSAGE =
            "OPENAI_API_KEY is not set. Export it (or load it with direnv, see README) before starting the application.";

    OpenAiApiKeyCheck(@Value("${spring.ai.openai.api-key:}") String apiKey) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException(MISSING_KEY_MESSAGE);
        }
    }
}
