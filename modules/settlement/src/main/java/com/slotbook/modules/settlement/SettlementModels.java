package com.slotbook.modules.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class SettlementModels {

    public record BatchSummary(long id, String closedAt) {
    }

    public record BatchLine(String instructor, String charges, String refunds, String payout) {
    }

    public record BatchDetail(long id, String closedAt, List<String> paymentEventIds, List<BatchLine> lines) {
    }

    public record SettleResponse(Long batchId) {
    }

    public record SettlementPayment(
            UUID eventId,
            long paymentId,
            long bookingId,
            long slotId,
            String instructor,
            String kind,
            BigDecimal amount,
            Instant occurredAt,
            Long batchId
    ) {
    }
}
