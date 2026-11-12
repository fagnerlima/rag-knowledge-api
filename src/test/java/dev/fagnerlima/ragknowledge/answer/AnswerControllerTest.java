package dev.fagnerlima.ragknowledge.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import dev.fagnerlima.ragknowledge.shared.web.ApiExceptionHandler;

@WebMvcTest(AnswerController.class)
@ExtendWith(OutputCaptureExtension.class)
@Import(ApiExceptionHandler.class)
class AnswerControllerTest {

    private static final String ENDPOINT = "/api/v1/answers";
    private static final String DISTINCT_QUESTION = "zebra-marker question about records";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AnswerService answerService;

    @Test
    void returnsTheAnswerContract() {
        given(answerService.answer(new AskQuestionRequest("What is a record?")))
                .willReturn(new AnswerResponse("A data carrier.", Confidence.MEDIUM, List.of()));

        assertThat(post("{\"question\": \"What is a record?\"}"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"answer": "A data carrier.", "confidence": "MEDIUM", "sources": []}
                        """);
    }

    @Test
    void rejectsABlankQuestionWithProblemDetails() {
        assertBadRequestProblem(post("{\"question\": \"  \"}"));
    }

    @Test
    void rejectsAMissingQuestion() {
        assertBadRequestProblem(post("{}"));
    }

    @Test
    void acceptsAQuestionAtTheMaximumLength() {
        given(answerService.answer(any()))
                .willReturn(new AnswerResponse("ok", Confidence.LOW, List.of()));

        assertThat(post(questionOfLength(AskQuestionRequest.MAX_QUESTION_LENGTH))).hasStatusOk();
    }

    @Test
    void rejectsAQuestionAboveTheMaximumLengthWithoutEchoingIt() {
        String tooLong = DISTINCT_QUESTION + "a".repeat(AskQuestionRequest.MAX_QUESTION_LENGTH);

        MvcTestResult result = post("{\"question\": \"" + tooLong + "\"}");

        assertBadRequestProblem(result);
        assertThat(result).bodyText().doesNotContain(DISTINCT_QUESTION);
    }

    @Test
    void rejectsMalformedJson() {
        assertBadRequestProblem(post("{\"question\": "));
    }

    @Test
    void mapsModelFailuresToBadGatewayWithoutLeakingDetails(CapturedOutput output) {
        given(answerService.answer(any()))
                .willThrow(new LlmResponseException("The language model call failed",
                        new IllegalStateException("secret upstream detail")));

        MvcTestResult result = post("{\"question\": \"" + DISTINCT_QUESTION + "\"}");

        assertThat(result)
                .hasStatus(HttpStatus.BAD_GATEWAY)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.type")
                            .isEqualTo(ApiExceptionHandler.LLM_UNAVAILABLE_TYPE.toString());
                    assertThat(json).extractingPath("$.title").isEqualTo("Language model unavailable");
                });
        assertThat(result).bodyText().doesNotContain("secret upstream detail", DISTINCT_QUESTION);
        assertThat(output).contains(IllegalStateException.class.getName())
                .doesNotContain("secret upstream detail", DISTINCT_QUESTION);
    }

    private void assertBadRequestProblem(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title").isEqualTo("Bad Request");
        verifyNoInteractions(answerService);
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static String questionOfLength(int length) {
        return "{\"question\": \"" + "a".repeat(length) + "\"}";
    }
}
