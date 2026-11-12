package dev.fagnerlima.ragknowledge.shared.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.fagnerlima.ragknowledge.answer.LlmResponseException;

@RestControllerAdvice
public class ApiExceptionHandler {

    public static final URI LLM_UNAVAILABLE_TYPE = URI.create(
            "https://github.com/fagnerlima/rag-knowledge-api/blob/main/docs/api-contract.md#llm-unavailable");

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LlmResponseException.class)
    ProblemDetail handleLlmResponse(LlmResponseException exception) {
        // Only exception types are logged: messages may carry the model output or upstream bodies,
        // and the question is user content. The types still tell auth, rate limit and parse errors apart.
        log.warn("{} (causes: {})", exception.getMessage(), causeTypes(exception));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The answer could not be generated. Try again later.");
        problem.setType(LLM_UNAVAILABLE_TYPE);
        problem.setTitle("Language model unavailable");

        return problem;
    }

    private static List<String> causeTypes(Throwable exception) {
        List<String> types = new ArrayList<>();

        for (Throwable cause = exception.getCause(); cause != null && types.size() < 5; cause = cause.getCause()) {
            types.add(cause.getClass().getName());
        }

        return types;
    }
}
