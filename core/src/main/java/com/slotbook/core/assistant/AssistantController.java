package com.slotbook.core.assistant;

import com.slotbook.contract.CurrentMember;
import com.slotbook.contract.MemberView;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AssistantController {

    private final CurrentMember current;
    private final AssistantService assistant;

    public AssistantController(CurrentMember current, AssistantService assistant) {
        this.current = current;
        this.assistant = assistant;
    }

    @PostMapping("/api/assistant/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        MemberView me = current.require();
        String reply = assistant.chat(me, request.messages());
        return new ChatResponse(reply);
    }
}
