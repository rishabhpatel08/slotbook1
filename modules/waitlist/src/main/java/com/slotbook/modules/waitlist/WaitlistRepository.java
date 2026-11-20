package com.slotbook.modules.waitlist;

import com.slotbook.contract.CentreClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
class WaitlistRepository {

    record Entry(long id, long slotId, long memberId, Instant joinedAt) {
    }

    private final JdbcClient jdbc;
    private final CentreClock clock;

    WaitlistRepository(JdbcClient jdbc, CentreClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Adds a member to the waitlist. Returns the 1-based position, or empty if already on the list. */
    Optional<Integer> join(long slotId, long memberId) {
        int inserted = jdbc.sql("""
                        insert into waitlist_entries (slot_id, member_id, joined_at)
                        values (:slot, :member, :now)
                        on conflict (slot_id, member_id) do nothing
                        """)
                .param("slot", slotId)
                .param("member", memberId)
                .param("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
                .update();
        if (inserted == 0) {
            return Optional.empty(); // already on the list
        }
        return Optional.of(position(slotId, memberId));
    }

    /** Removes a member from the waitlist. */
    void leave(long slotId, long memberId) {
        jdbc.sql("delete from waitlist_entries where slot_id = :slot and member_id = :member")
                .param("slot", slotId)
                .param("member", memberId)
                .update();
    }

    /** Returns the ordered entries for a slot. */
    List<Entry> entriesForSlot(long slotId) {
        return jdbc.sql("select id, slot_id, member_id, joined_at from waitlist_entries where slot_id = :slot order by id")
                .param("slot", slotId)
                .query((rs, i) -> new Entry(rs.getLong("id"), rs.getLong("slot_id"), rs.getLong("member_id"),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Returns the 1-based position of a member on a slot's waitlist, or 0 if not on it. */
    int position(long slotId, long memberId) {
        List<Entry> entries = entriesForSlot(slotId);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).memberId() == memberId) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Removes and returns the first member on the waitlist, or empty if the list is empty. */
    Optional<Entry> pollFirst(long slotId) {
        List<Entry> entries = jdbc.sql("""
                        select id, slot_id, member_id, joined_at
                        from waitlist_entries
                        where slot_id = :slot
                        order by id
                        limit 1
                        for update skip locked
                        """)
                .param("slot", slotId)
                .query((rs, i) -> new Entry(rs.getLong("id"), rs.getLong("slot_id"), rs.getLong("member_id"),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant()))
                .list();
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        Entry first = entries.get(0);
        jdbc.sql("delete from waitlist_entries where id = :id").param("id", first.id()).update();
        return Optional.of(first);
    }

    /** Clears all entries for a slot. */
    void clearSlot(long slotId) {
        jdbc.sql("delete from waitlist_entries where slot_id = :slot").param("slot", slotId).update();
    }

    /** Returns true if the event has already been processed (idempotency check), and records it if not. */
    boolean tryRecordEvent(java.util.UUID eventId) {
        int inserted = jdbc.sql("insert into waitlist_processed_events (event_id) values (:id) on conflict do nothing")
                .param("id", eventId)
                .update();
        return inserted == 1; // true = first time
    }

    /** Checks if a member already holds a confirmed booking for the slot (via the waitlist_entries or bookings). */
    boolean isOnWaitlist(long slotId, long memberId) {
        return jdbc.sql("select count(*) from waitlist_entries where slot_id = :slot and member_id = :member")
                .param("slot", slotId)
                .param("member", memberId)
                .query(Integer.class)
                .single() > 0;
    }
}
