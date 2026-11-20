package com.slotbook.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotbook.core.testing.SlotBookTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SlotBookTest
class NewMemberPlanTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void newMemberCanAccessMyPlanWithoutPlanOrBookings() throws Exception {
        MvcResult signupResult = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Priya Sharma",
                                  "email": "priya.sharma@slotbook.test",
                                  "password": "pass"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = mapper.readTree(signupResult.getResponse().getContentAsString());
        String token = json.get("token").asText();

        mvc.perform(get("/api/me/plan")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value(nullValue()))
                .andExpect(jsonPath("$.classesPerMonth").value(0))
                .andExpect(jsonPath("$.monthlyPrice").value(0.0))
                .andExpect(jsonPath("$.classesUsedThisMonth").value(0));
    }
}
