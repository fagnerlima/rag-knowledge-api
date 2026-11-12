package dev.fagnerlima.ragknowledge.answer;

/** The language model could not be called or returned an answer outside the contract. */
public class LlmResponseException extends RuntimeException {

    public LlmResponseException(String message) {
        super(message);
    }

    public LlmResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
