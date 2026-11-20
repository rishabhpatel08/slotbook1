package com.slotbook.modules.waitlist;

import com.slotbook.contract.BookingCancelled;
import com.slotbook.contract.BookingResult;
import com.slotbook.contract.Bookings;
import com.slotbook.contract.CentreClock;
import com.slotbook.contract.Notifier;
import com.slotbook.contract.SlotView;
import com.slotbook.contract.Slots;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

@Component
class WaitlistListener {

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    private final WaitlistRepository repo;
    private final Bookings bookings;
    private final Slots slots;
    private final Notifier notifier;
    private final CentreClock clock;

    WaitlistListener(WaitlistRepository repo, Bookings bookings, Slots slots, Notifier notifier, CentreClock clock) {
        this.repo = repo;
        this.bookings = bookings;
        this.slots = slots;
        this.notifier = notifier;
        this.clock = clock;
    }

    @EventListener
    void on(BookingCancelled event) {
        // Idempotency: skip if we already processed this event
        if (!repo.tryRecordEvent(event.eventId())) {
            return;
        }

        long slotId = event.slotId();

        // Try to book the first waitlisted member; if that fails, try the next, etc.
        while (true) {
            Optional<WaitlistRepository.Entry> next = repo.pollFirst(slotId);
            if (next.isEmpty()) {
                break; // nobody waiting
            }

            WaitlistRepository.Entry entry = next.get();
            BookingResult result = bookings.book(entry.memberId(), slotId);
            if (result.booked()) {
                // Notify the promoted member
                SlotView slot = slots.find(slotId).orElse(null);
                String className = slot != null
                        ? slot.batchName() + " on " + WHEN.format(slot.startsAt().atZone(clock.zone()))
                        : "your class";
                notifier.notify(entry.memberId(),
                        "Good news! A seat opened up for " + className + ". You've been booked in automatically.");
                break; // one cancellation books at most one person
            }
            // If booking failed (FULL, SLOT_STARTED, etc.), skip this member and try the next
        }
    }
}
