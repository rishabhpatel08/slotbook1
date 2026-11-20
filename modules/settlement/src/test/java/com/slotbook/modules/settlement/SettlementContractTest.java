package com.slotbook.modules.settlement;

import com.slotbook.contract.MemberView;
import com.slotbook.contract.PaymentRecorded;
import com.slotbook.core.testing.SlotBookTest;
import com.slotbook.core.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SlotBookTest
class SettlementContractTest {

    static final String BASE = "/api/modules/settlement/admin";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestHost host;

    @Autowired
    SettlementService settlementService;

    @Test
    void emptySettlementReturnsNullBatchId() throws Exception {
        String adminAuth = host.auth(host.admin());

        mvc.perform(post(BASE + "/settle").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(nullValue()));
    }

    @Test
    void nonAdminCannotAccessEndpoints() throws Exception {
        MemberView regular = host.newMember("Regular");
        String regularAuth = host.auth(regular);

        mvc.perform(post(BASE + "/settle").header("Authorization", regularAuth))
                .andExpect(status().isForbidden());

        mvc.perform(get(BASE + "/batches").header("Authorization", regularAuth))
                .andExpect(status().isForbidden());

        mvc.perform(get(BASE + "/batches/1").header("Authorization", regularAuth))
                .andExpect(status().isForbidden());
    }

    @Test
    void paymentsAreRecordedAndSettledWithCorrectPayout() throws Exception {
        String adminAuth = host.auth(host.admin());

        long slot = host.newSlot(5);
        MemberView member = host.newMember("Payer");

        UUID charge1 = UUID.randomUUID();
        UUID charge2 = UUID.randomUUID();
        UUID refund1 = UUID.randomUUID();

        // Record 2 charges of 500.00 each
        settlementService.recordPayment(new PaymentRecorded(charge1, 101L, 1L, slot, member.id(), "CHARGE", "500.00", Instant.now()));
        settlementService.recordPayment(new PaymentRecorded(charge2, 102L, 2L, slot, member.id(), "CHARGE", "500.00", Instant.now()));

        // Record 1 refund of 100.00
        settlementService.recordPayment(new PaymentRecorded(refund1, 103L, 1L, slot, member.id(), "REFUND", "100.00", Instant.now()));

        // Duplicate delivery of charge1 should be safely ignored
        settlementService.recordPayment(new PaymentRecorded(charge1, 101L, 1L, slot, member.id(), "CHARGE", "500.00", Instant.now()));

        // Close batch
        String res = mvc.perform(post(BASE + "/settle").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isNumber())
                .andReturn().getResponse().getContentAsString();

        long batchId = Long.parseLong(res.replaceAll(".*\"batchId\":(\\d+).*", "$1"));

        // Check list of batches
        mvc.perform(get(BASE + "/batches").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Check batch details:
        // charges: 1000.00, refunds: 100.00, net: 900.00, payout: 630.00 (70% of 900.00)
        mvc.perform(get(BASE + "/batches/" + batchId).header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(batchId))
                .andExpect(jsonPath("$.paymentEventIds", hasSize(3)))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].charges").value("1000.00"))
                .andExpect(jsonPath("$.lines[0].refunds").value("100.00"))
                .andExpect(jsonPath("$.lines[0].payout").value("630.00"));
    }
}
