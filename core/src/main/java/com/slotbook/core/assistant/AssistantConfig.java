package com.slotbook.core.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantConfig {

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient geminiLlmClient(ObjectMapper mapper) {
        return new GeminiLlmClient(mapper);
    }
}
