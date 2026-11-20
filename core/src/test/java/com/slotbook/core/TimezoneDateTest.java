package com.slotbook.core;

import com.slotbook.contract.MemberView;
import com.slotbook.core.config.Db;
import com.slotbook.core.testing.SlotBookTest;
import com.slotbook.core.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SlotBookTest
class TimezoneDateTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestHost host;

    @Autowired
    JdbcClient jdbc;

    @Test
    void monday5amSlotAppearsUnderMondayInMyBookings() throws Exception {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        LocalDate nextMonday = LocalDate.now(kolkata).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        // 5:00 AM IST on next Monday is 23:30 UTC on Sunday the day before
        Instant monday5am = nextMonday.atTime(5, 0).atZone(kolkata).toInstant();
        long slotId = host.newSlot(10, monday5am);

        MemberView member = host.newMember("MorningYogi");
        host.book(member, slotId);
        String auth = host.auth(member);

        // Verify My Bookings returns date as next Monday in Asia/Kolkata, NOT Sunday (UTC)
        mvc.perform(get("/api/bookings/mine").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value(nextMonday.toString()))
                .andExpect(jsonPath("$[0].slot.id").value(slotId));
    }

    @Test
    void monday5amSlotAppearsInTodayQueryForMonday() throws Exception {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        LocalDate nextMonday = LocalDate.now(kolkata).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        Instant monday5am = nextMonday.atTime(5, 0).atZone(kolkata).toInstant();
        long slotId = host.newSlot(10, monday5am);

        // Querying for Monday should find this slot
        mvc.perform(get("/api/slots").param("date", nextMonday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) slotId)));

        // Querying for Sunday (the day before) should NOT include Monday's 5:00 AM class
        LocalDate sunday = nextMonday.minusDays(1);
        mvc.perform(get("/api/slots").param("date", sunday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem((int) slotId))));
    }

    @Test
    void firstOfMonth5amSlotCountsInMonthlyPlanUsage() throws Exception {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        LocalDate firstOfMonth = LocalDate.now(kolkata).withDayOfMonth(1);
        Instant firstOfMonth5am = firstOfMonth.atTime(5, 0).atZone(kolkata).toInstant();

        // Insert slot and booking directly into DB to test plan counting
        long slotId = host.newSlot(10, firstOfMonth5am);
        MemberView member = host.newMember("PlanUser");

        jdbc.sql("insert into bookings (slot_id, member_id, status, amount) values (:slot, :member, 'CONFIRMED', 100.0)")
                .param("slot", slotId)
                .param("member", member.id())
                .update();

        String auth = host.auth(member);

        mvc.perform(get("/api/me/plan").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classesUsedThisMonth").value(1));
    }
}
