package com.slotbook.core.assistant;

import java.util.List;

public interface LlmClient {

    LlmTypes.LlmStepResult call(String systemInstruction,
                               List<LlmTypes.Content> contents,
                               List<LlmTypes.FunctionDeclaration> functions);
}
