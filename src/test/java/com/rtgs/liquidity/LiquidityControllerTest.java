package com.rtgs.liquidity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rtgs.payment.Participant;
import com.rtgs.payment.ParticipantRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LiquidityControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ParticipantRegistry registry;
    @Autowired LiquidityService liquidity;

    @Test void injectionEndpointSettlesQueuedPayment() throws Exception {
        registry.register(new Participant("API_A", "Bank A"));
        registry.register(new Participant("API_B", "Bank B"));
        liquidity.open("API_A", 0);
        liquidity.open("API_B", 0);
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"paymentId":"api-p","sourceParticipantId":"API_A","destinationParticipantId":"API_B",
                 "amountMinor":10,"priority":"NORMAL"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(post("/api/liquidity/API_A/inject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settledFromQueue").value(1));
        mvc.perform(get("/api/payments/api-p")).andExpect(jsonPath("$.status").value("SETTLED"));
    }
}
