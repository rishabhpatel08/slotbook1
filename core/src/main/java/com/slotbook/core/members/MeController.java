package com.slotbook.core.members;

import com.slotbook.contract.CentreClock;
import com.slotbook.contract.CurrentMember;
import com.slotbook.contract.MemberView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

@RestController
public class MeController {

    private final CurrentMember current;
    private final MemberRepository members;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final CentreClock centreClock;

    public MeController(CurrentMember current, MemberRepository members, JdbcClient jdbc, Clock clock, CentreClock centreClock) {
        this.current = current;
        this.members = members;
        this.jdbc = jdbc;
        this.clock = clock;
        this.centreClock = centreClock;
    }

    @GetMapping("/api/me")
    public MemberView me() {
        return current.require();
    }

    @GetMapping("/api/me/plan")
    public Map<String, Object> myPlan() {
        MemberView me = current.require();
        Member member = members.findById(me.id()).orElseThrow();
        Plan plan = members.findPlan(member.planId()).orElseThrow();

        LocalDate firstOfMonth = LocalDate.ofInstant(clock.instant(), centreClock.zone()).withDayOfMonth(1);
        int used = jdbc.sql("""
                        select count(*) from bookings b join slots s on s.id = b.slot_id
                        where b.member_id = :member and b.status = 'CONFIRMED'
                          and s.starts_at >= :from and s.starts_at < :to
                        """)
                .param("member", me.id())
                .param("from", firstOfMonth.atStartOfDay(centreClock.zone()).toOffsetDateTime())
                .param("to", firstOfMonth.plusMonths(1).atStartOfDay(centreClock.zone()).toOffsetDateTime())
                .query(Integer.class)
                .single();

        return Map.of(
                "plan", plan.name(),
                "classesPerMonth", plan.classesPerMonth(),
                "monthlyPrice", plan.monthlyPrice(),
                "classesUsedThisMonth", used);
    }
}
