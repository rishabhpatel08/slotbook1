package com.slotbook.core.assistant;

import java.util.List;

public record ChatRequest(List<ChatMessage> messages) {

    public record ChatMessage(String role, String content) {
    }
}
