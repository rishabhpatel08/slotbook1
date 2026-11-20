package com.slotbook.core.assistant;

public class LlmProviderException extends RuntimeException {
    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmProviderException(String message) {
        super(message);
    }
}
