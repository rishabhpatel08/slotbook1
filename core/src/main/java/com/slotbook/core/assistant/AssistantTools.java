package com.slotbook.core.assistant;

import com.slotbook.contract.BookingResult;
import com.slotbook.contract.CentreClock;
import com.slotbook.contract.MemberView;
import com.slotbook.core.bookings.Booking;
import com.slotbook.core.bookings.BookingRepository;
import com.slotbook.core.bookings.BookingService;
import com.slotbook.core.slots.Slot;
import com.slotbook.core.slots.SlotRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

@Component
public class AssistantTools {

    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final CentreClock centreClock;

    public AssistantTools(SlotRepository slots,
                          BookingRepository bookings,
                          BookingService bookingService,
                          CentreClock centreClock) {
        this.slots = slots;
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.centreClock = centreClock;
    }

    public static List<LlmTypes.FunctionDeclaration> declarations() {
        return List.of(
                new LlmTypes.FunctionDeclaration(
                        "list_classes",
                        "List available classes and slots on a specific date in the centre's timezone.",
                        Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "date", Map.of(
                                                "type", "STRING",
                                                "description", "The date to inspect in YYYY-MM-DD format"
                                        )
                                ),
                                "required", List.of("date")
                        )
                ),
                new LlmTypes.FunctionDeclaration(
                        "book",
                        "Book a confirmed seat in a class slot for the current member. Only call when the slot is unambiguously chosen.",
                        Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "slotId", Map.of(
                                                "type", "INTEGER",
                                                "description", "The unique slot ID to book"
                                        )
                                ),
                                "required", List.of("slotId")
                        )
                ),
                new LlmTypes.FunctionDeclaration(
                        "cancel",
                        "Cancel an existing confirmed booking belonging to the current member. Only call when the booking is unambiguously chosen.",
                        Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "bookingId", Map.of(
                                                "type", "INTEGER",
                                                "description", "The unique booking ID to cancel"
                                        )
                                ),
                                "required", List.of("bookingId")
                        )
                ),
                new LlmTypes.FunctionDeclaration(
                        "list_my_bookings",
                        "List all bookings belonging to the current member, including status and class details.",
                        Map.of(
                                "type", "OBJECT",
                                "properties", Map.of()
                        )
                )
        );
    }

    public Object listClasses(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return Map.of("error", "Date is required in YYYY-MM-DD format");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            return Map.of("error", "Invalid date format '" + dateStr + "'. Expected YYYY-MM-DD.");
        }

        ZoneId zone = centreClock.zone();
        var from = date.atStartOfDay(zone).toInstant();
        var to = date.plusDays(1).atStartOfDay(zone).toInstant();

        List<Slot> slotList = slots.between(from, to);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Slot s : slotList) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slotId", s.id());
            item.put("batchName", s.batchName());
            item.put("instructor", s.instructor());
            item.put("startsAt", s.startsAt().atZone(zone).toLocalDateTime().toString());
            item.put("durationMinutes", s.durationMinutes());
            item.put("capacity", s.capacity());
            item.put("booked", s.booked());
            item.put("availableSeats", Math.max(0, s.capacity() - s.booked()));
            item.put("price", s.price());
            result.add(item);
        }
        return result;
    }

    public Object book(MemberView me, long slotId) {
        BookingResult result = bookingService.book(me.id(), slotId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", result.booked());
        res.put("outcome", result.outcome().name());
        if (result.bookingId() != null) {
            res.put("bookingId", result.bookingId());
        }
        return res;
    }

    public Object cancel(MemberView me, long bookingId) {
        Optional<Booking> opt = bookings.find(bookingId);
        if (opt.isEmpty()) {
            return Map.of("success", false, "error", "Booking not found");
        }
        Booking b = opt.get();
        if (b.memberId() != me.id()) {
            return Map.of("success", false, "error", "Unauthorized: this booking does not belong to you");
        }
        if (!b.isConfirmed()) {
            return Map.of("success", false, "error", "Booking is already cancelled");
        }
        try {
            double refund = bookingService.cancel(me, bookingId);
            return Map.of("success", true, "bookingId", bookingId, "refund", refund);
        } catch (ResponseStatusException e) {
            return Map.of("success", false, "error", e.getReason() != null ? e.getReason() : "Could not cancel booking");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "Could not cancel booking");
        }
    }

    public Object listMyBookings(MemberView me) {
        List<Booking> memberBookings = bookings.forMember(me.id());
        ZoneId zone = centreClock.zone();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Booking b : memberBookings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bookingId", b.id());
            item.put("slotId", b.slotId());
            item.put("status", b.status());
            item.put("amount", b.amount());
            slots.find(b.slotId()).ifPresent(s -> {
                item.put("batchName", s.batchName());
                item.put("instructor", s.instructor());
                item.put("startsAt", s.startsAt().atZone(zone).toLocalDateTime().toString());
            });
            result.add(item);
        }
        return result;
    }
}
