package com.slotbook.core.bookings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Cancellation policy: 90% back if the booking is cancelled at least 12 hours before the class starts,
 * otherwise 50%.
 */
public final class RefundCalculator {

    public static final BigDecimal EARLY_REFUND_RATE = new BigDecimal("0.90");
    public static final BigDecimal LATE_REFUND_RATE = new BigDecimal("0.50");
    public static final Duration EARLY_CANCELLATION_THRESHOLD = Duration.ofHours(12);

    private RefundCalculator() {
    }

    /**
     * Determines whether a cancellation qualifies for the early refund rate
     * (at least 12 hours before the class starts in the centre's zone).
     */
    public static boolean isEarly(Instant now, Instant classStart, ZoneId zone) {
        ZonedDateTime nowZoned = now.atZone(zone);
        ZonedDateTime startZoned = classStart.atZone(zone);
        Duration duration = Duration.between(nowZoned, startZoned);
        return duration.compareTo(EARLY_CANCELLATION_THRESHOLD) >= 0;
    }

    /** The refund for an amount paid, in rupees, rounded half-up to the nearest paisa. */
    public static BigDecimal refundFor(BigDecimal paid, boolean early) {
        if (paid == null || paid.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rate = early ? EARLY_REFUND_RATE : LATE_REFUND_RATE;
        return paid.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** The refund for an amount paid, in rupees, rounded half-up to the nearest paisa. */
    public static double refundFor(double paid, boolean early) {
        return refundFor(BigDecimal.valueOf(paid), early).doubleValue();
    }
}
