package com.slotbook.modules.settlement;

import com.slotbook.contract.CentreClock;
import com.slotbook.contract.PaymentRecorded;
import com.slotbook.contract.SlotView;
import com.slotbook.contract.Slots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository repository;
    private final Slots slots;
    private final CentreClock centreClock;

    public SettlementService(SettlementRepository repository, Slots slots, CentreClock centreClock) {
        this.repository = repository;
        this.slots = slots;
        this.centreClock = centreClock;
    }

    @Transactional
    public void recordPayment(PaymentRecorded payment) {
        SlotView slot = slots.find(payment.slotId())
                .orElseThrow(() -> new IllegalStateException("Slot not found for id: " + payment.slotId()));

        repository.savePayment(
                payment.eventId(),
                payment.paymentId(),
                payment.bookingId(),
                payment.slotId(),
                slot.instructor(),
                payment.kind(),
                new BigDecimal(payment.amount()),
                payment.occurredAt()
        );
    }

    @Transactional
    public synchronized Long settle() {
        Instant now = centreClock.now();
        long batchId = repository.createBatch(now);
        int assignedCount = repository.assignBatchToUnsettled(batchId);

        if (assignedCount == 0) {
            repository.deleteBatch(batchId);
            return null;
        }

        List<SettlementModels.SettlementPayment> payments = repository.findPaymentsByBatchId(batchId);
        Map<String, BigDecimal[]> instructorSums = new TreeMap<>();

        for (SettlementModels.SettlementPayment p : payments) {
            BigDecimal[] sums = instructorSums.computeIfAbsent(
                    p.instructor(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}
            );
            if ("CHARGE".equalsIgnoreCase(p.kind())) {
                sums[0] = sums[0].add(p.amount());
            } else if ("REFUND".equalsIgnoreCase(p.kind())) {
                sums[1] = sums[1].add(p.amount());
            }
        }

        for (var entry : instructorSums.entrySet()) {
            String instructor = entry.getKey();
            BigDecimal charges = entry.getValue()[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal refunds = entry.getValue()[1].setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = charges.subtract(refunds);
            BigDecimal payout = net.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.HALF_UP);

            repository.insertLine(batchId, instructor, charges, refunds, payout);
        }

        log.info("Closed settlement batch {} with {} payments across {} instructors",
                batchId, assignedCount, instructorSums.size());
        return batchId;
    }

    public List<SettlementModels.BatchSummary> listBatches() {
        return repository.listBatches();
    }

    public Optional<SettlementModels.BatchDetail> getBatch(long id) {
        return repository.findBatchById(id).map(summary -> {
            List<String> eventIds = repository.findEventIdsByBatchId(id);
            List<SettlementModels.BatchLine> lines = repository.findLinesByBatchId(id);
            return new SettlementModels.BatchDetail(summary.id(), summary.closedAt(), eventIds, lines);
        });
    }
}
