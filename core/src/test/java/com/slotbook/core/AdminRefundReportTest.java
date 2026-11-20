package com.slotbook.core;

import com.slotbook.core.testing.SlotBookTest;
import com.slotbook.core.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the admin refund-report endpoint returns totals that match
 * the payment gateway's October 2026 settlement (docs/gateway-refunds-2026-10.csv).
 *
 * Uses real rows seeded by V3__october_history.sql (corrected by V4__fix_october_refunds.sql).
 */
@SlotBookTest
class AdminRefundReportTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestHost host;

    @Test
    void octoberRefundTotalMatchesGateway() throws Exception {
        String auth = host.auth(host.admin());

        mvc.perform(get("/api/admin/reports/refunds")
                        .param("month", "2026-10")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.refunds").value(30))
                .andExpect(jsonPath("$.total").value(6853.50));
    }

    @Test
    void refundReportRequiresAdmin() throws Exception {
        // A regular (non-admin) member should be forbidden
        String auth = host.auth(host.newMember("RegularUser"));

        mvc.perform(get("/api/admin/reports/refunds")
                        .param("month", "2026-10")
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void refundReportRequiresSignIn() throws Exception {
        mvc.perform(get("/api/admin/reports/refunds")
                        .param("month", "2026-10"))
                .andExpect(status().isUnauthorized());
    }
}
