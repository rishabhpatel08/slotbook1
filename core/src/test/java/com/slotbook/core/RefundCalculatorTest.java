package com.slotbook.core;

import com.slotbook.core.bookings.RefundCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundCalculatorTest {

    private final ZoneId centreZone = ZoneId.of("Asia/Kolkata");

    @Test
    void earlyCancellationRefunds90Percent() {
        assertEquals(405.00, RefundCalculator.refundFor(450.00, true), 0.001);
        assertEquals(300.00, RefundCalculator.refundFor(333.33, true), 0.001);
        assertEquals(121.91, RefundCalculator.refundFor(135.45, true), 0.001);
    }

    @Test
    void lateCancellationRefundsHalf() {
        assertEquals(299.50, RefundCalculator.refundFor(599.00, false), 0.001);
        assertEquals(225.00, RefundCalculator.refundFor(450.00, false), 0.001);
        assertEquals(67.73, RefundCalculator.refundFor(135.45, false), 0.001);
    }

    @Test
    void bigDecimalPrecisionAndRoundingToNearestPaisa() {
        assertEquals(new BigDecimal("405.00"), RefundCalculator.refundFor(new BigDecimal("450.00"), true));
        assertEquals(new BigDecimal("300.00"), RefundCalculator.refundFor(new BigDecimal("333.33"), true));
        assertEquals(new BigDecimal("121.91"), RefundCalculator.refundFor(new BigDecimal("135.45"), true));
        assertEquals(new BigDecimal("299.50"), RefundCalculator.refundFor(new BigDecimal("599.00"), false));
        assertEquals(new BigDecimal("225.00"), RefundCalculator.refundFor(new BigDecimal("450.00"), false));
        assertEquals(new BigDecimal("67.73"), RefundCalculator.refundFor(new BigDecimal("135.45"), false));
        assertEquals(new BigDecimal("0.00"), RefundCalculator.refundFor(BigDecimal.ZERO, true));
    }

    @Test
    void exact12HourBoundaryDeterminesEarlyVsLate() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");

        // Exactly 12 hours before class: qualifies for early refund
        Instant exactly12h = now.plus(Duration.ofHours(12));
        assertTrue(RefundCalculator.isEarly(now, exactly12h, centreZone),
                "Cancellation exactly 12 hours before class should qualify for early refund");

        // 20 hours before class (customer complaint T1.4 scenario): qualifies for early refund
        Instant twentyHoursBefore = now.plus(Duration.ofHours(20));
        assertTrue(RefundCalculator.isEarly(now, twentyHoursBefore, centreZone),
                "Cancellation 20 hours before class should qualify for early refund");

        // Just under 12 hours (1 second under): late cancellation
        Instant justUnder12h = exactly12h.minusSeconds(1);
        assertFalse(RefundCalculator.isEarly(now, justUnder12h, centreZone),
                "Cancellation just under 12 hours before class should be late cancellation");

        // 6 hours before class: late cancellation
        Instant sixHoursBefore = now.plus(Duration.ofHours(6));
        assertFalse(RefundCalculator.isEarly(now, sixHoursBefore, centreZone),
                "Cancellation 6 hours before class should be late cancellation");
    }
}
