package com.slotbook.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotbook.contract.CentreClock;
import com.slotbook.contract.MemberView;
import com.slotbook.core.assistant.*;
import com.slotbook.core.bookings.Booking;
import com.slotbook.core.bookings.BookingRepository;
import com.slotbook.core.slots.Slot;
import com.slotbook.core.slots.SlotRepository;
import com.slotbook.core.testing.SlotBookTest;
import com.slotbook.core.testing.TestHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SlotBookTest
@org.springframework.context.annotation.Import(AssistantTest.Config.class)
class AssistantTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    static class FakeLlmClient implements LlmClient {
        private final Queue<LlmTypes.LlmStepResult> queue = new ArrayDeque<>();
        private final List<LlmTypes.Content> recordedContents = new ArrayList<>();

        public void enqueue(LlmTypes.LlmStepResult result) {
            queue.add(result);
        }

        public void reset() {
            queue.clear();
            recordedContents.clear();
        }

        public List<LlmTypes.Content> getRecordedContents() {
            return recordedContents;
        }

        @Override
        public LlmTypes.LlmStepResult call(String systemInstruction,
                                           List<LlmTypes.Content> contents,
                                           List<LlmTypes.FunctionDeclaration> functions) {
            recordedContents.clear();
            recordedContents.addAll(contents);
            if (queue.isEmpty()) {
                return new LlmTypes.LlmStepResult("No canned response available", List.of());
            }
            return queue.poll();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TestHost host;

    @Autowired
    FakeLlmClient fakeLlm;

    @Autowired
    BookingRepository bookings;

    @Autowired
    SlotRepository slots;

    @Autowired
    CentreClock centreClock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AssistantTools assistantTools;

    @Autowired
    ObjectMapper mapper;

    private MemberView meera;
    private String meeraAuth;

    @BeforeEach
    void setUp() {
        fakeLlm.reset();
        meera = host.newMember("Meera Assistant");
        meeraAuth = host.auth(meera);
    }

    @Test
    void unauthenticatedCallsAreRejected() throws Exception {
        mvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "user", "content": "hello"}]
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assistantCannotActForAnotherMember() throws Exception {
        // Create another member and a booking belonging to them
        MemberView otherMember = host.newMember("Other Person");
        Instant startsAt = centreClock.now().plus(java.time.Duration.ofDays(2));
        long slotId = host.newSlot(10, startsAt);
        long otherBookingId = host.book(otherMember, slotId);

        // Model attempts to call cancel on otherMember's booking
        fakeLlm.enqueue(new LlmTypes.LlmStepResult("", List.of(
                new LlmTypes.FunctionCall("cancel", Map.of("bookingId", otherBookingId))
        )));
        // Then model gives a final reply after seeing tool error
        fakeLlm.enqueue(new LlmTypes.LlmStepResult("I cannot cancel that booking because it does not belong to your account.", List.of()));

        mvc.perform(post("/api/assistant/chat")
                        .header("Authorization", meeraAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "user", "content": "cancel booking for me"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("I cannot cancel that booking because it does not belong to your account."));

        // Verify that other member's booking is STILL confirmed in database!
        Booking b = bookings.find(otherBookingId).orElseThrow();
        assertEquals("CONFIRMED", b.status(), "Other member's booking must remain confirmed!");
    }

    @Test
    void asksClarifyingQuestionWhenAmbiguous() throws Exception {
        // User asks "Book yoga tomorrow" when multiple slots exist
        // The assistant must ask for clarification instead of booking
        fakeLlm.enqueue(new LlmTypes.LlmStepResult(
                "There are two Yoga classes tomorrow: 7:00 AM with Priya and 5:00 PM with Rahul. Which one would you prefer to book?",
                List.of()
        ));

        mvc.perform(post("/api/assistant/chat")
                        .header("Authorization", meeraAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "user", "content": "book yoga tomorrow"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("There are two Yoga classes tomorrow: 7:00 AM with Priya and 5:00 PM with Rahul. Which one would you prefer to book?"));

        // Verify no booking was accidentally created
        List<Booking> meeraBookings = bookings.forMember(meera.id());
        assertTrue(meeraBookings.isEmpty(), "No booking should be created when ambiguous");
    }

    @Test
    void successfulToolCallFlowToBookClass() throws Exception {
        Instant startsAt = centreClock.now().plus(java.time.Duration.ofDays(2));
        long slotId = host.newSlot(10, startsAt);

        // Step 1: Model calls book(slotId)
        fakeLlm.enqueue(new LlmTypes.LlmStepResult("", List.of(
                new LlmTypes.FunctionCall("book", Map.of("slotId", slotId))
        )));
        // Step 2: Model confirms booking to user
        fakeLlm.enqueue(new LlmTypes.LlmStepResult("You are successfully booked for the class!", List.of()));

        mvc.perform(post("/api/assistant/chat")
                        .header("Authorization", meeraAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "user", "content": "Book Maths Olympiad for me"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("You are successfully booked for the class!"));

        // Verify Meera now has a confirmed booking for this slot
        assertTrue(bookings.hasConfirmed(meera.id(), slotId), "Meera should have a confirmed booking for the slot");
    }

    @Test
    void missingApiKeyReturnsClearMessage() {
        GeminiLlmClient missingKeyClient = new GeminiLlmClient(mapper, null, "gemini-2.5-flash-lite");
        AssistantService serviceWithMissingKey = new AssistantService(missingKeyClient, assistantTools, centreClock);

        String reply = serviceWithMissingKey.chat(
                meera,
                List.of(new ChatRequest.ChatMessage("user", "Hello"))
        );

        assertTrue(reply.contains("LLM_API_KEY environment variable is missing"), "Should mention missing LLM_API_KEY");
    }
}
