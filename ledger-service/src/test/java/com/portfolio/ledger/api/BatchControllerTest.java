package com.portfolio.ledger.api;

import com.portfolio.ledger.batch.DailyBalanceRepository;
import com.portfolio.ledger.batch.NightlyCloseConflictException;
import com.portfolio.ledger.batch.NightlyCloseResult;
import com.portfolio.ledger.batch.NightlyCloseService;
import com.portfolio.ledger.domain.AccountNotFoundException;
import com.portfolio.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchController.class)
@Import(SecurityConfig.class)
class BatchControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean NightlyCloseService closeService;
    @MockitoBean DailyBalanceRepository balances;
    @MockitoBean AccountAccess access;
    @MockitoBean LedgerService ledger;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String BODY = "{\"businessDate\":\"2025-06-30\"}";

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_canTriggerTheClose() throws Exception {
        when(closeService.run(LocalDate.of(2025, 6, 30), false)).thenReturn(
                new NightlyCloseResult(7, LocalDate.of(2025, 6, 30), "COMPLETED", "COMPLETED", "", 12, 0));

        mvc.perform(post("/api/v1/admin/nightly-close").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountsProcessed").value(12))
                .andExpect(jsonPath("$.exitCode").value("COMPLETED"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void ordinaryUser_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/admin/nightly-close").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void alreadyClosedDay_is409() throws Exception {
        when(closeService.run(any(), eq(false))).thenThrow(new NightlyCloseConflictException("already closed"));

        mvc.perform(post("/api/v1/admin/nightly-close").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NIGHTLY_CLOSE_CONFLICT"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void missingDate_is400() throws Exception {
        when(closeService.run(eq(null), eq(false))).thenThrow(new IllegalArgumentException("businessDate is required"));

        mvc.perform(post("/api/v1/admin/nightly-close").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "alice")
    void dailyBalances_checkOwnership() throws Exception {
        UUID id = UUID.randomUUID();
        when(balances.findRecent(id, 30)).thenReturn(List.of());

        mvc.perform(get("/api/v1/accounts/" + id + "/daily-balances")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "mallory")
    void dailyBalances_ofSomeoneElsesAccount_is404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new AccountNotFoundException(id)).when(access).require(any(), eq(id));

        mvc.perform(get("/api/v1/accounts/" + id + "/daily-balances")).andExpect(status().isNotFound());
    }
}
