package dev.fagnerlima.ragknowledge.answer;

import java.util.List;

public record AnswerResponse(String answer, Confidence confidence, List<SourceCitation> sources) {

    public AnswerResponse {
        sources = List.copyOf(sources);
    }
}
