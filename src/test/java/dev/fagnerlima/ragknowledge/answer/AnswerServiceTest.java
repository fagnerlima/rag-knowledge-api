package dev.fagnerlima.ragknowledge.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

class AnswerServiceTest {

    private static final AskQuestionRequest QUESTION = new AskQuestionRequest("What is a Java record?");

    @Test
    void mapsStructuredModelOutputToTheAnswerContract() {
        FakeChatModel model = FakeChatModel.replying("""
                {"answer": "An immutable data carrier class.", "confidence": "HIGH"}
                """);

        AnswerResponse response = serviceWith(model).answer(QUESTION);

        assertThat(response.answer()).isEqualTo("An immutable data carrier class.");
        assertThat(response.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void sendsTheSystemRulesAndTheQuestionAsSeparateMessages() {
        FakeChatModel model = FakeChatModel.replying("""
                {"answer": "ok", "confidence": "LOW"}
                """);

        serviceWith(model).answer(QUESTION);

        var messages = model.lastPrompt().getInstructions();
        assertThat(messages).anySatisfy(message -> {
            assertThat(message.getMessageType()).isEqualTo(MessageType.SYSTEM);
            assertThat(message.getText()).isEqualTo(AnswerService.SYSTEM_PROMPT);
        });
        assertThat(messages).anySatisfy(message -> {
            assertThat(message.getMessageType()).isEqualTo(MessageType.USER);
            assertThat(message.getText()).contains(QUESTION.question());
        });
    }

    @Test
    void requestsNativeStructuredOutputWithTheAnswerSchema() {
        FakeChatModel model = FakeChatModel.replying("""
                {"answer": "ok", "confidence": "LOW"}
                """);

        serviceWith(model).answer(QUESTION);

        assertThat(model.lastPrompt().getOptions())
                .isInstanceOfSatisfying(StructuredOutputChatOptions.class, options -> assertThat(options.getOutputSchema())
                        .contains("\"answer\"", "\"confidence\"", "\"HIGH\"", "\"MEDIUM\"", "\"LOW\""));
    }

    @Test
    void wrapsModelCallFailures() {
        FakeChatModel model = FakeChatModel.failingWith(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> serviceWith(model).answer(QUESTION))
                .isInstanceOf(LlmResponseException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAReplyWithoutContentSuchAsARefusal() {
        FakeChatModel model = FakeChatModel.replying(null);

        assertThatThrownBy(() -> serviceWith(model).answer(QUESTION))
                .isInstanceOf(LlmResponseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all",
            "{\"answer\": \"ok\", \"confidence\": \"CERTAIN\"}",
            "{\"answer\": \"   \", \"confidence\": \"HIGH\"}",
            "{\"answer\": \"ok\"}",
            "{\"confidence\": \"HIGH\"}"
    })
    void rejectsModelOutputOutsideTheContract(String modelOutput) {
        FakeChatModel model = FakeChatModel.replying(modelOutput);

        assertThatThrownBy(() -> serviceWith(model).answer(QUESTION))
                .isInstanceOf(LlmResponseException.class);
    }

    private static AnswerService serviceWith(FakeChatModel model) {
        return new AnswerService(ChatClient.builder(model));
    }
}
