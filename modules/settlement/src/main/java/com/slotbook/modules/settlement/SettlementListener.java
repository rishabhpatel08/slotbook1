package com.slotbook.modules.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotbook.contract.PaymentRecorded;
import com.slotbook.contract.Topics;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
public class SettlementListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementListener.class);

    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;

    public SettlementListener(SettlementService settlementService, ObjectMapper objectMapper) {
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
    }

    @JmsListener(destination = Topics.PAYMENTS, containerFactory = Topics.LISTENER_FACTORY, subscription = "settlement")
    public void on(Message message) throws Exception {
        if (message instanceof TextMessage textMessage) {
            String json = textMessage.getText();
            PaymentRecorded payment = objectMapper.readValue(json, PaymentRecorded.class);
            settlementService.recordPayment(payment);
        }
    }
}
