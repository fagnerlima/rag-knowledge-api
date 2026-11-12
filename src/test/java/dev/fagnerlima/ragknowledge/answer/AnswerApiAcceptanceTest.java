package dev.fagnerlima.ragknowledge.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import dev.fagnerlima.ragknowledge.shared.web.ApiExceptionHandler;

/**
 * Acceptance tests of docs/api-contract.md through the full application context: real controller, validation,
 * exception handling, ChatClient auto-configuration and Spring AI structured output, with only the OpenAI
 * {@link ChatModel} replaced. The base URL points to a closed local port so a wiring mistake cannot reach OpenAI.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AnswerApiAcceptanceTest {

    private static final String ENDPOINT = "/api/v1/answers";
    private static final String DISTINCT_QUESTION = "quokka-marker question about sealed types";
    private static final String DISTINCT_MODEL_OUTPUT = "platypus-marker raw model output";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private SwitchableChatModel chatModel;

    @BeforeEach
    void defaultReply() {
        chatModel.use(FakeChatModel.replying("""
                {"answer": "Sealed types restrict which classes may extend them.", "confidence": "HIGH"}
                """));
    }

    // AC1: valid question returns 200 with the answer contract.
    @Test
    void ac1_validQuestionReturnsTheAnswerContractThroughTheRealChatClient() {
        assertThat(postJson("{\"question\": \"" + DISTINCT_QUESTION + "\"}"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .isStrictlyEqualTo("""
                        {"answer": "Sealed types restrict which classes may extend them.",
                         "confidence": "HIGH", "sources": []}
                        """);
    }

    // AC1 + contract compatibility: unknown request fields do not break the request.
    @Test
    void ac1_unknownRequestFieldsAreIgnored() {
        assertThat(postJson("{\"question\": \"What is a record?\", \"locale\": \"pt-BR\"}")).hasStatusOk();
    }

    // AC2: the auto-configured ChatClient sends the LlmAnswer JSON Schema as native structured output.
    @Test
    void ac2_requestsNativeStructuredOutputThroughTheAutoConfiguredChatClient() {
        FakeChatModel model = FakeChatModel.replying("{\"answer\": \"ok\", \"confidence\": \"LOW\"}");
        chatModel.use(model);

        assertThat(postJson("{\"question\": \"What is a record?\"}")).hasStatusOk();

        assertThat(model.lastPrompt().getOptions())
                .isInstanceOfSatisfying(StructuredOutputChatOptions.class, options -> assertThat(options.getOutputSchema())
                        .contains("\"answer\"", "\"confidence\"", "\"HIGH\"", "\"MEDIUM\"", "\"LOW\""));
    }

    // AC3: invalid input is a 400 problem without echoing the question, and the model is never called.
    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"question\": null}",
            "{\"question\": \"\"}",
            "{\"question\": \"   \"}",
            "{\"question\": \"" + DISTINCT_QUESTION + "\"",
            "",
            "[]"
    })
    void ac3_invalidInputIsABadRequestProblem(String body) {
        FakeChatModel model = FakeChatModel.replying("{\"answer\": \"ok\", \"confidence\": \"LOW\"}");
        chatModel.use(model);

        MvcTestResult result = postJson(body);

        assertBadRequestProblem(result);
        assertThat(result).bodyText().doesNotContain(DISTINCT_QUESTION);
        assertThat(model.lastPrompt()).isNull();
    }

    @Test
    void ac3_tooLongQuestionIsABadRequestProblemWithoutEchoInBodyOrLogs(CapturedOutput output) {
        String tooLong = DISTINCT_QUESTION + "a".repeat(AskQuestionRequest.MAX_QUESTION_LENGTH);

        MvcTestResult result = postJson("{\"question\": \"" + tooLong + "\"}");

        assertBadRequestProblem(result);
        assertThat(result).bodyText().doesNotContain(DISTINCT_QUESTION);
        assertThat(output).doesNotContain(DISTINCT_QUESTION);
    }

    @Test
    void ac3_questionAtTheMaximumLengthReachesTheModel() {
        assertThat(postJson("{\"question\": \"" + "a".repeat(AskQuestionRequest.MAX_QUESTION_LENGTH) + "\"}"))
                .hasStatusOk();
    }

    // Documented in the contract as other standard errors: wrong content type and method.
    @Test
    void unsupportedContentTypeIsAnUnsupportedMediaTypeProblem() {
        MvcTestResult result = mvc.post().uri(ENDPOINT)
                .contentType(MediaType.TEXT_PLAIN)
                .content(DISTINCT_QUESTION)
                .exchange();

        assertThat(result)
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyText().doesNotContain(DISTINCT_QUESTION);
    }

    @Test
    void getIsAMethodNotAllowedProblem() {
        assertThat(mvc.get().uri(ENDPOINT).exchange())
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // AC4: model failures and unusable output become a 502 llm-unavailable problem without leaks.
    @Test
    void ac4_modelCallFailureIsALlmUnavailableProblemWithoutLeaks(CapturedOutput output) {
        chatModel.use(FakeChatModel.failingWith(new IllegalStateException(DISTINCT_MODEL_OUTPUT)));

        assertLlmUnavailableWithoutLeaks(postJson("{\"question\": \"" + DISTINCT_QUESTION + "\"}"), output);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            DISTINCT_MODEL_OUTPUT,
            "{\"answer\": \"" + DISTINCT_MODEL_OUTPUT + "\", \"confidence\": \"CERTAIN\"}",
            "{\"answer\": \"   \", \"confidence\": \"HIGH\"}",
            "{\"answer\": \"" + DISTINCT_MODEL_OUTPUT + "\"}",
            "{\"confidence\": \"HIGH\"}",
            "null",
            ""
    })
    void ac4_outputOutsideTheContractIsALlmUnavailableProblemWithoutLeaks(String modelOutput,
            CapturedOutput output) {
        chatModel.use(FakeChatModel.replying(modelOutput));

        assertLlmUnavailableWithoutLeaks(postJson("{\"question\": \"" + DISTINCT_QUESTION + "\"}"), output);
    }

    @Test
    void ac4_nullModelContentIsALlmUnavailableProblem(CapturedOutput output) {
        chatModel.use(FakeChatModel.replying(null));

        assertLlmUnavailableWithoutLeaks(postJson("{\"question\": \"" + DISTINCT_QUESTION + "\"}"), output);
    }

    // AC5: loopback by default and only the health endpoint exposed.
    @Test
    void ac5_serverBindsToLoopbackByDefault(@Autowired ServerProperties serverProperties) throws Exception {
        Assumptions.assumeTrue(System.getenv("SERVER_ADDRESS") == null, "SERVER_ADDRESS overrides the default");

        assertThat(serverProperties.getAddress()).isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void ac5_environmentEndpointIsNotExposed() {
        assertThat(mvc.get().uri("/actuator/env").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac5_actuatorDiscoveryListsOnlyHealth() {
        assertThat(mvc.get().uri("/actuator").exchange())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$._links")
                .asMap()
                .containsOnlyKeys("self", "health", "health-path");
    }

    private void assertLlmUnavailableWithoutLeaks(MvcTestResult result, CapturedOutput output) {
        assertThat(result)
                .hasStatus(HttpStatus.BAD_GATEWAY)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.type")
                            .isEqualTo(ApiExceptionHandler.LLM_UNAVAILABLE_TYPE.toString());
                    assertThat(json).extractingPath("$.title").isEqualTo("Language model unavailable");
                    assertThat(json).extractingPath("$.status").isEqualTo(502);
                });
        assertThat(result).bodyText().doesNotContain(DISTINCT_QUESTION, DISTINCT_MODEL_OUTPUT);
        // Positive control: proves the handler's log line was captured, so the absence checks mean something.
        assertThat(output).contains("causes: [")
                .doesNotContain(DISTINCT_QUESTION, DISTINCT_MODEL_OUTPUT);
    }

    private void assertBadRequestProblem(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    // RFC 9457: an absent type means about:blank, and Spring omits it.
                    assertThat(json).doesNotHavePath("$.type");
                    assertThat(json).extractingPath("$.title").isEqualTo("Bad Request");
                });
    }

    private MvcTestResult postJson(String body) {
        return mvc.post().uri(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    /** Replaces the OpenAI chat model in the context; each test chooses the fake it delegates to. */
    static class SwitchableChatModel implements ChatModel {

        private final AtomicReference<FakeChatModel> delegate = new AtomicReference<>(FakeChatModel.replying(null));

        void use(FakeChatModel model) {
            delegate.set(model);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return delegate.get().call(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.get().getOptions();
        }
    }

    @TestConfiguration
    static class FakeModelConfiguration {

        @Bean
        @Primary
        SwitchableChatModel switchableChatModel() {
            return new SwitchableChatModel();
        }
    }
}
