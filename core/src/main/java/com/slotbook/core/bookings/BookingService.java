package com.slotbook.core.bookings;

import com.slotbook.contract.BookingCancelled;
import com.slotbook.contract.BookingConfirmed;
import com.slotbook.contract.BookingResult;
import com.slotbook.contract.BookingResult.Outcome;
import com.slotbook.contract.BookingView;
import com.slotbook.contract.CancelResult;
import com.slotbook.contract.Bookings;
import com.slotbook.contract.CentreClock;
import com.slotbook.contract.ForbiddenException;
import com.slotbook.contract.MemberView;
import com.slotbook.core.events.EventPublisher;
import com.slotbook.core.payments.GatewayTimeoutException;
import com.slotbook.core.payments.PaymentGateway;
import com.slotbook.core.payments.PaymentRepository;
import com.slotbook.core.slots.Slot;
import com.slotbook.core.slots.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService implements Bookings {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final SlotRepository slots;
    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final EventPublisher events;
    private final Clock clock;
    private final CentreClock centreClock;

    public BookingService(BookingRepository bookings, SlotRepository slots, PaymentGateway gateway,
                          PaymentRepository payments, EventPublisher events, Clock clock, CentreClock centreClock) {
        this.bookings = bookings;
        this.slots = slots;
        this.gateway = gateway;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
        this.centreClock = centreClock;
    }

    @Override
    public BookingResult book(long memberId, long slotId) {
        Optional<Slot> found = slots.find(slotId);
        if (found.isEmpty()) {
            return new BookingResult(Outcome.SLOT_NOT_FOUND, null);
        }
        Slot slot = found.get();
        if (!slot.startsAt().isAfter(clock.instant())) {
            return new BookingResult(Outcome.SLOT_STARTED, null);
        }
        if (bookings.hasConfirmed(memberId, slotId)) {
            return new BookingResult(Outcome.ALREADY_BOOKED, null);
        }
        if (bookings.countConfirmed(slotId) >= slot.capacity()) {
            return new BookingResult(Outcome.FULL, null);
        }

        // Take the payment before creating the booking, so nobody holds a seat they have not paid for.
        // The gateway is flaky in production: if it times out, try once more before giving up.
        try {
            gateway.charge(memberId, slot.price());
        } catch (GatewayTimeoutException e) {
            log.warn("{}; retrying", e.getMessage());
            gateway.charge(memberId, slot.price());
        }
        long bookingId = bookings.insert(slotId, memberId, slot.price());
        payments.record(bookingId, "CHARGE", slot.price());

        events.publish(new BookingConfirmed(UUID.randomUUID(), bookingId, slotId, memberId, clock.instant()));
        return new BookingResult(Outcome.BOOKED, bookingId);
    }

    @Override
    public Optional<BookingView> find(long bookingId) {
        return bookings.find(bookingId).map(BookingService::view);
    }

    @Override
    public List<BookingView> confirmedForMember(long memberId) {
        return bookings.forMember(memberId).stream().filter(Booking::isConfirmed).map(BookingService::view).toList();
    }

    @Override
    public CancelResult cancel(long memberId, long bookingId) {
        Booking booking = bookings.find(bookingId).orElse(null);
        if (booking == null) {
            return new CancelResult(CancelResult.Outcome.NOT_FOUND, null);
        }
        if (booking.memberId() != memberId) {
            return new CancelResult(CancelResult.Outcome.NOT_YOURS, null);
        }
        if (!booking.isConfirmed()) {
            return new CancelResult(CancelResult.Outcome.ALREADY_CANCELLED, null);
        }
        try {
            double refund = cancel(new MemberView(memberId, "", "", false), bookingId);
            return new CancelResult(CancelResult.Outcome.CANCELLED, java.math.BigDecimal.valueOf(refund).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        } catch (ResponseStatusException e) {
            return new CancelResult(CancelResult.Outcome.CLASS_STARTED, null);
        }
    }

    /** Cancels a booking and refunds it. Members can cancel their own bookings; admins can cancel any. */
    public double cancel(MemberView who, long bookingId) {
        Booking booking = bookings.find(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such booking"));
        if (booking.memberId() != who.id() && !who.admin()) {
            throw new ForbiddenException("Not your booking");
        }
        if (!booking.isConfirmed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already cancelled");
        }
        Slot slot = slots.find(booking.slotId()).orElseThrow();
        Instant now = clock.instant();
        ZoneId zone = centreClock.zone();
        ZonedDateTime nowZoned = now.atZone(zone);
        ZonedDateTime startsAtZoned = slot.startsAt().atZone(zone);
        if (!startsAtZoned.isAfter(nowZoned)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The class has already started");
        }

        boolean early = RefundCalculator.isEarly(now, slot.startsAt(), zone);
        BigDecimal refundAmount = RefundCalculator.refundFor(BigDecimal.valueOf(booking.amount()), early);
        double refund = refundAmount.doubleValue();

        bookings.markCancelled(bookingId, now);
        payments.record(bookingId, "REFUND", refund);
        gateway.refund(bookingId, refund);

        events.publish(new BookingCancelled(UUID.randomUUID(), bookingId, booking.slotId(), booking.memberId(), now));
        return refund;
    }

    private static BookingView view(Booking b) {
        return new BookingView(b.id(), b.slotId(), b.memberId(), b.status());
    }
}
