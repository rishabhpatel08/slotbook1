package com.slotbook.modules.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementService settlementService;

    public SettlementScheduler(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(fixedDelayString = "${slotbook.settlement.interval-seconds:300}", timeUnit = TimeUnit.SECONDS, initialDelay = 5)
    public void runPeriodicSettlement() {
        try {
            settlementService.settle();
        } catch (Exception e) {
            log.warn("Periodic settlement attempt encountered error: {}", e.getMessage());
        }
    }
}
