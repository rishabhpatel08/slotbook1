package com.slotbook.core.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public class LlmTypes {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeminiRequest(
            Content systemInstruction,
            List<Content> contents,
            List<ToolWrapper> tools
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolWrapper(
            List<FunctionDeclaration> functionDeclarations
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionDeclaration(
            String name,
            String description,
            Map<String, Object> parameters
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
            String role,
            List<Part> parts
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(
            String text,
            FunctionCall functionCall,
            FunctionResponse functionResponse
    ) {
        public static Part ofText(String text) {
            return new Part(text, null, null);
        }

        public static Part ofFunctionCall(String name, Map<String, Object> args) {
            return new Part(null, new FunctionCall(name, args), null);
        }

        public static Part ofFunctionResponse(String name, Object response) {
            return new Part(null, null, new FunctionResponse(name, Map.of("result", response)));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(
            String name,
            Map<String, Object> args
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionResponse(
            String name,
            Map<String, Object> response
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeminiResponse(
            List<Candidate> candidates,
            PromptFeedback promptFeedback
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            Content content,
            String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptFeedback(
            String blockReason
    ) {}

    public record LlmStepResult(
            String text,
            List<FunctionCall> functionCalls
    ) {}
}
