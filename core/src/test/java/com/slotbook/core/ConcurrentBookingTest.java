package com.slotbook.core;

import com.slotbook.contract.BookingResult;
import com.slotbook.contract.Bookings;
import com.slotbook.contract.MemberView;
import com.slotbook.core.testing.SlotBookTest;
import com.slotbook.core.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SlotBookTest
class ConcurrentBookingTest {

    @Autowired
    Bookings bookings;

    @Autowired
    TestHost host;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void concurrentBookingsDoNotOverbookLastSeat() throws Exception {
        // Slot with capacity 2, 1 seat already filled -> only 1 seat left
        long slotId = host.newSlot(2);
        host.book(host.newMember("Existing"), slotId);
        assertEquals(1, host.confirmedCount(slotId));

        int threadCount = 10;
        List<MemberView> candidates = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            candidates.add(host.newMember("Contender" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<BookingResult> results = Collections.synchronizedList(new ArrayList<>());

        for (MemberView candidate : candidates) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    results.add(bookings.book(candidate.id(), slotId));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactly one contender should have booked successfully
        long bookedCount = results.stream().filter(BookingResult::booked).count();
        assertEquals(1, bookedCount, "Exactly one thread should have booked the last seat");
        assertEquals(2, host.confirmedCount(slotId), "Slot confirmed count must never exceed capacity");
    }

    @Test
    void concurrentBookingsBySameMemberDoesNotDuplicate() throws Exception {
        long slotId = host.newSlot(5);
        MemberView member = host.newMember("DoubleBooker");

        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<BookingResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    results.add(bookings.book(member.id(), slotId));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        long bookedCount = results.stream().filter(BookingResult::booked).count();
        assertEquals(1, bookedCount, "Member should only be booked once");
        assertEquals(1, host.confirmedCount(slotId), "Total bookings for member should be 1");
    }

    @Test
    void adminAttendanceQueryDeduplicatesMembers() throws Exception {
        long slotId = host.newSlot(10);
        MemberView member = host.newMember("RepeatAttendee");
        host.book(member, slotId);

        // Even if an attendance query is run, member appears exactly once
        String auth = host.auth(host.admin());
        mvc.perform(get("/api/admin/slots/" + slotId + "/attendance").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].memberId").value(member.id()));
    }
}
