package com.slotbook.modules.waitlist;

import com.slotbook.contract.MemberView;
import com.slotbook.core.testing.SlotBookTest;
import com.slotbook.core.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Additional waitlist tests beyond the contract test. */
@SlotBookTest
class WaitlistExtraTest {

    static final String BASE = "/api/modules/waitlist/slots/";

    @Autowired MockMvc mvc;
    @Autowired TestHost host;

    /** Delivering the same BookingCancelled event twice must not book two people. */
    @Test
    void duplicateEventDeliveryBooksOnlyOnce() throws Exception {
        long slot = host.newSlot(1);
        List<Long> booked = host.fill(slot);
        MemberView first = host.newMember("DupFirst");
        MemberView second = host.newMember("DupSecond");
        mvc.perform(post(BASE + slot + "/join").header("Authorization", host.auth(first)))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + slot + "/join").header("Authorization", host.auth(second)))
                .andExpect(status().isOk());

        // Cancel and deliver events multiple times (simulates at-least-once)
        host.cancel(booked.get(0));
        host.deliverEvents();
        host.deliverEvents();
        host.deliverEvents(); // extra delivery — should be idempotent

        // Only the first member should have been booked
        org.junit.jupiter.api.Assertions.assertTrue(
                host.hasConfirmedBooking(first, slot), "first waitlisted member should be booked");
        assertFalse(
                host.hasConfirmedBooking(second, slot), "second waitlisted member should NOT be booked from the same cancellation");
    }

    /** Cancelling a booking on a slot with an empty waitlist does not blow up. */
    @Test
    void cancellationWithEmptyWaitlistIsHarmless() throws Exception {
        long slot = host.newSlot(2);
        MemberView member = host.newMember("Solo");
        long bookingId = host.book(member, slot);

        // Cancel — waitlist is empty, the event listener should do nothing
        host.cancel(bookingId);
        host.deliverEvents();
        host.deliverEvents();

        // Slot should just have one fewer confirmed booking; no errors
        assertEquals(0, host.confirmedCount(slot));
    }

    /** A member cannot join the waitlist twice for the same slot. */
    @Test
    void cannotJoinTwice() throws Exception {
        long slot = host.newSlot(1);
        host.fill(slot);
        String auth = host.auth(host.newMember("DoubleJoin"));

        mvc.perform(post(BASE + slot + "/join").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + slot + "/join").header("Authorization", auth))
                .andExpect(status().isConflict());
    }

    /** A member who already holds a booking cannot join the waitlist. */
    @Test
    void cannotJoinIfAlreadyBooked() throws Exception {
        long slot = host.newSlot(2);
        MemberView member = host.newMember("AlreadyBooked");
        host.book(member, slot);
        // Fill the remaining seat
        host.fill(slot);

        mvc.perform(post(BASE + slot + "/join").header("Authorization", host.auth(member)))
                .andExpect(status().isConflict());
    }

    /** Privacy: members should not see other members' real names in the waitlist view. */
    @Test
    void memberViewHidesOtherNames() throws Exception {
        long slot = host.newSlot(1);
        host.fill(slot);
        MemberView first = host.newMember("RealNameFirst");
        MemberView second = host.newMember("RealNameSecond");
        mvc.perform(post(BASE + slot + "/join").header("Authorization", host.auth(first)))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + slot + "/join").header("Authorization", host.auth(second)))
                .andExpect(status().isOk());

        // Second member sees their own name, but first member's name is hidden
        mvc.perform(get(BASE + slot).header("Authorization", host.auth(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.myPosition").value(2))
                .andExpect(jsonPath("$.entries[0].name").value("Member 1"))
                .andExpect(jsonPath("$.entries[1].name").value("RealNameSecond"));
    }

    /** Endpoints require sign-in. */
    @Test
    void endpointsNeedSignIn() throws Exception {
        mvc.perform(post(BASE + "1/join")).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE + "1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "1")).andExpect(status().isUnauthorized());
    }

    /** Admin endpoints require admin role. */
    @Test
    void adminEndpointsRequireAdmin() throws Exception {
        String auth = host.auth(host.newMember("RegularUser"));
        mvc.perform(get("/api/modules/waitlist/admin/slots/1").header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/modules/waitlist/admin/slots/1").header("Authorization", auth))
                .andExpect(status().isForbidden());
    }
}
