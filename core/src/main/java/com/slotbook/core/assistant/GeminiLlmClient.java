package com.slotbook.core.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);
    private static final String DEFAULT_MODEL = "gemini-1.5-flash";
    private static final List<String> FALLBACK_MODELS = List.of(
            "gemini-1.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash-8b"
    );

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public GeminiLlmClient(ObjectMapper mapper) {
        this(mapper,
             System.getenv("LLM_API_KEY"),
             System.getenv().getOrDefault("LLM_MODEL", DEFAULT_MODEL));
    }

    public GeminiLlmClient(ObjectMapper mapper, String apiKey, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
    }

    @Override
    public LlmTypes.LlmStepResult call(String systemInstruction,
                                       List<LlmTypes.Content> contents,
                                       List<LlmTypes.FunctionDeclaration> functions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new MissingApiKeyException("LLM_API_KEY environment variable is not configured");
        }

        try {
            LlmTypes.Content sysContent = systemInstruction != null
                    ? new LlmTypes.Content("system", List.of(LlmTypes.Part.ofText(systemInstruction)))
                    : null;

            List<LlmTypes.ToolWrapper> tools = (functions != null && !functions.isEmpty())
                    ? List.of(new LlmTypes.ToolWrapper(functions))
                    : null;

            LlmTypes.GeminiRequest requestBody = new LlmTypes.GeminiRequest(sysContent, contents, tools);
            String jsonPayload = mapper.writeValueAsString(requestBody);

            List<String> modelsToTry = new ArrayList<>();
            modelsToTry.add(model);
            for (String fb : FALLBACK_MODELS) {
                if (!modelsToTry.contains(fb)) {
                    modelsToTry.add(fb);
                }
            }

            HttpResponse<String> response = null;
            String lastError = null;

            for (String currentModel : modelsToTry) {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/" + currentModel
                        + ":generateContent?key=" + apiKey;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 400) {
                    break;
                }
                lastError = "Model " + currentModel + " returned " + response.statusCode() + ": " + response.body();
                log.warn("Gemini call failed with model {}: status {}", currentModel, response.statusCode());
            }

            if (response == null || response.statusCode() >= 400) {
                log.error("All Gemini model attempts failed. Last error: {}", lastError);
                throw new LlmProviderException("Gemini API call failed: " + lastError);
            }

            LlmTypes.GeminiResponse geminiResponse = mapper.readValue(response.body(), LlmTypes.GeminiResponse.class);
            if (geminiResponse.candidates() == null || geminiResponse.candidates().isEmpty()) {
                return new LlmTypes.LlmStepResult("", List.of());
            }

            LlmTypes.Content candidateContent = geminiResponse.candidates().get(0).content();
            if (candidateContent == null || candidateContent.parts() == null) {
                return new LlmTypes.LlmStepResult("", List.of());
            }

            StringBuilder textBuilder = new StringBuilder();
            List<LlmTypes.FunctionCall> calls = new ArrayList<>();

            for (LlmTypes.Part part : candidateContent.parts()) {
                if (part.text() != null) {
                    textBuilder.append(part.text());
                }
                if (part.functionCall() != null) {
                    calls.add(part.functionCall());
                }
            }

            return new LlmTypes.LlmStepResult(textBuilder.toString().trim(), calls);
        } catch (MissingApiKeyException e) {
            throw e;
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to invoke Gemini LLM", e);
            throw new LlmProviderException("Communication with LLM failed: " + e.getMessage(), e);
        }
    }
}
