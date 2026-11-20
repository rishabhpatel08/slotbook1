package com.slotbook.modules.settlement;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SettlementRepository {

    private final JdbcClient jdbc;

    private static final RowMapper<SettlementModels.SettlementPayment> PAYMENT_MAPPER = (rs, i) ->
            new SettlementModels.SettlementPayment(
                    (UUID) rs.getObject("event_id"),
                    rs.getLong("payment_id"),
                    rs.getLong("booking_id"),
                    rs.getLong("slot_id"),
                    rs.getString("instructor"),
                    rs.getString("kind"),
                    rs.getBigDecimal("amount"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getObject("batch_id", Long.class)
            );

    private static final RowMapper<SettlementModels.BatchSummary> BATCH_SUMMARY_MAPPER = (rs, i) ->
            new SettlementModels.BatchSummary(
                    rs.getLong("id"),
                    rs.getTimestamp("closed_at").toInstant().toString()
            );

    private static final RowMapper<SettlementModels.BatchLine> BATCH_LINE_MAPPER = (rs, i) ->
            new SettlementModels.BatchLine(
                    rs.getString("instructor"),
                    rs.getBigDecimal("charges").setScale(2).toPlainString(),
                    rs.getBigDecimal("refunds").setScale(2).toPlainString(),
                    rs.getBigDecimal("payout").setScale(2).toPlainString()
            );

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void savePayment(UUID eventId, long paymentId, long bookingId, long slotId,
                            String instructor, String kind, BigDecimal amount, Instant occurredAt) {
        jdbc.sql("""
                insert into settlement_payments (event_id, payment_id, booking_id, slot_id, instructor, kind, amount, occurred_at)
                values (:eventId, :paymentId, :bookingId, :slotId, :instructor, :kind, :amount, :occurredAt)
                on conflict (event_id) do nothing
                """)
                .param("eventId", eventId)
                .param("paymentId", paymentId)
                .param("bookingId", bookingId)
                .param("slotId", slotId)
                .param("instructor", instructor)
                .param("kind", kind)
                .param("amount", amount)
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();
    }

    public long createBatch(Instant closedAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into settlement_batches (closed_at) values (:closedAt)")
                .param("closedAt", Timestamp.from(closedAt))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public int assignBatchToUnsettled(long batchId) {
        return jdbc.sql("update settlement_payments set batch_id = :batchId where batch_id is null")
                .param("batchId", batchId)
                .update();
    }

    public void deleteBatch(long batchId) {
        jdbc.sql("delete from settlement_batches where id = :id")
                .param("id", batchId)
                .update();
    }

    public List<SettlementModels.SettlementPayment> findPaymentsByBatchId(long batchId) {
        return jdbc.sql("select * from settlement_payments where batch_id = :batchId order by occurred_at asc, event_id asc")
                .param("batchId", batchId)
                .query(PAYMENT_MAPPER)
                .list();
    }

    public void insertLine(long batchId, String instructor, BigDecimal charges, BigDecimal refunds, BigDecimal payout) {
        jdbc.sql("""
                insert into settlement_lines (batch_id, instructor, charges, refunds, payout)
                values (:batchId, :instructor, :charges, :refunds, :payout)
                """)
                .param("batchId", batchId)
                .param("instructor", instructor)
                .param("charges", charges)
                .param("refunds", refunds)
                .param("payout", payout)
                .update();
    }

    public List<SettlementModels.BatchSummary> listBatches() {
        return jdbc.sql("select id, closed_at from settlement_batches order by id asc")
                .query(BATCH_SUMMARY_MAPPER)
                .list();
    }

    public Optional<SettlementModels.BatchSummary> findBatchById(long id) {
        return jdbc.sql("select id, closed_at from settlement_batches where id = :id")
                .param("id", id)
                .query(BATCH_SUMMARY_MAPPER)
                .optional();
    }

    public List<String> findEventIdsByBatchId(long batchId) {
        return jdbc.sql("select event_id from settlement_payments where batch_id = :batchId order by occurred_at asc, event_id asc")
                .param("batchId", batchId)
                .query((rs, i) -> rs.getString("event_id"))
                .list();
    }

    public List<SettlementModels.BatchLine> findLinesByBatchId(long batchId) {
        return jdbc.sql("select instructor, charges, refunds, payout from settlement_lines where batch_id = :batchId order by instructor asc")
                .param("batchId", batchId)
                .query(BATCH_LINE_MAPPER)
                .list();
    }
}
