package com.slotbook.core.assistant;

import com.slotbook.contract.CentreClock;
import com.slotbook.contract.MemberView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final int MAX_MESSAGES = 20;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_ROUNDS = 5;

    private final LlmClient llmClient;
    private final AssistantTools tools;
    private final CentreClock centreClock;

    public AssistantService(LlmClient llmClient, AssistantTools tools, CentreClock centreClock) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.centreClock = centreClock;
    }

    public String chat(MemberView me, List<ChatRequest.ChatMessage> incomingMessages) {
        if (incomingMessages == null || incomingMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Messages list cannot be empty");
        }
        if (incomingMessages.size() > MAX_MESSAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Conversation exceeds maximum allowed messages (" + MAX_MESSAGES + ")");
        }
        for (ChatRequest.ChatMessage msg : incomingMessages) {
            if (msg.content() != null && msg.content().length() > MAX_MESSAGE_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message exceeds length limit of " + MAX_MESSAGE_LENGTH + " characters");
            }
        }

        LocalDate today = centreClock.now().atZone(centreClock.zone()).toLocalDate();
        String systemInstruction = """
                You are SlotBook's AI booking assistant. You help coaching centre members manage class bookings.
                Centre Time Zone: %s
                Today's Date: %s (%s)
                Tomorrow's Date: %s
                
                Mandatory Operational & Safety Rules:
                1. You act ONLY on behalf of the signed-in member. Never ask for or mention a memberId.
                2. Use `list_classes(date)` with date in YYYY-MM-DD format to check scheduled classes.
                3. Use `list_my_bookings()` to see the member's existing bookings.
                4. Ambiguity Rule: If the user's request matches more than one class or booking, or zero classes/bookings, DO NOT call book or cancel. Instead, ask a clear clarifying question detailing the available choices (class name, time, instructor).
                5. Only call `book(slotId)` when the user has confirmed a single, unambiguous class slot.
                6. Only call `cancel(bookingId)` when the user has confirmed a single, unambiguous booking.
                7. Keep all responses concise, courteous, and accurate.
                """.formatted(centreClock.zone(), today, today.getDayOfWeek(), today.plusDays(1));

        List<LlmTypes.Content> contents = new ArrayList<>();
        for (ChatRequest.ChatMessage m : incomingMessages) {
            String role = "assistant".equalsIgnoreCase(m.role()) || "model".equalsIgnoreCase(m.role()) ? "model" : "user";
            contents.add(new LlmTypes.Content(role, List.of(LlmTypes.Part.ofText(m.content() != null ? m.content() : ""))));
        }

        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                LlmTypes.LlmStepResult step = llmClient.call(systemInstruction, contents, AssistantTools.declarations());

                if (step.functionCalls().isEmpty()) {
                    return !step.text().isBlank() ? step.text() : "I have processed your request.";
                }

                List<LlmTypes.Part> modelParts = new ArrayList<>();
                if (!step.text().isBlank()) {
                    modelParts.add(LlmTypes.Part.ofText(step.text()));
                }
                for (LlmTypes.FunctionCall call : step.functionCalls()) {
                    modelParts.add(new LlmTypes.Part(null, call, null));
                }
                contents.add(new LlmTypes.Content("model", modelParts));

                for (LlmTypes.FunctionCall call : step.functionCalls()) {
                    Object toolResult = executeTool(me, call);
                    contents.add(new LlmTypes.Content("function", List.of(
                            LlmTypes.Part.ofFunctionResponse(call.name(), toolResult)
                    )));
                }
            }
            return "I could not complete your request within the allowed steps. Please try again.";
        } catch (MissingApiKeyException e) {
            log.warn("Assistant unavailable: {}", e.getMessage());
            return "The booking assistant is not configured: LLM_API_KEY environment variable is missing.";
        } catch (LlmProviderException e) {
            log.error("Assistant provider failure: {}", e.getMessage());
            return "The booking assistant is temporarily unavailable. Please try again later.";
        }
    }

    private Object executeTool(MemberView me, LlmTypes.FunctionCall call) {
        String name = call.name();
        Map<String, Object> args = call.args() != null ? call.args() : Map.of();

        try {
            return switch (name) {
                case "list_classes" -> {
                    String date = args.get("date") != null ? args.get("date").toString() : null;
                    yield tools.listClasses(date);
                }
                case "book" -> {
                    Object slotObj = args.get("slotId");
                    if (slotObj instanceof Number num) {
                        yield tools.book(me, num.longValue());
                    } else if (slotObj != null) {
                        yield tools.book(me, Long.parseLong(slotObj.toString()));
                    }
                    yield Map.of("success", false, "error", "Missing or invalid slotId");
                }
                case "cancel" -> {
                    Object bookingObj = args.get("bookingId");
                    if (bookingObj instanceof Number num) {
                        yield tools.cancel(me, num.longValue());
                    } else if (bookingObj != null) {
                        yield tools.cancel(me, Long.parseLong(bookingObj.toString()));
                    }
                    yield Map.of("success", false, "error", "Missing or invalid bookingId");
                }
                case "list_my_bookings" -> tools.listMyBookings(me);
                default -> Map.of("error", "Unknown tool: " + name);
            };
        } catch (Exception e) {
            log.warn("Tool execution failed for {}: {}", name, e.getMessage());
            return Map.of("error", e.getMessage() != null ? e.getMessage() : "Tool execution failed");
        }
    }
}
