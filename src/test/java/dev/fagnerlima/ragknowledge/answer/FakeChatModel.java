package dev.fagnerlima.ragknowledge.answer;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Replaces the OpenAI boundary: returns a canned reply and records the prompt it received. */
class FakeChatModel implements ChatModel {

    private final Supplier<String> reply;
    private Prompt lastPrompt;

    private FakeChatModel(Supplier<String> reply) {
        this.reply = reply;
    }

    static FakeChatModel replying(String content) {
        return new FakeChatModel(() -> content);
    }

    static FakeChatModel failingWith(RuntimeException exception) {
        return new FakeChatModel(() -> {
            throw exception;
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        lastPrompt = prompt;

        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.get()))));
    }

    /** OpenAI options, so the native structured output advisor can attach the JSON Schema as it does in production. */
    @Override
    public ChatOptions getOptions() {
        return OpenAiChatOptions.builder().build();
    }

    Prompt lastPrompt() {
        return lastPrompt;
    }
}
