package dev.fagnerlima.ragknowledge.answer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskQuestionRequest(
        @NotBlank
        @Size(max = AskQuestionRequest.MAX_QUESTION_LENGTH)
        String question) {

    public static final int MAX_QUESTION_LENGTH = 2000;
}
