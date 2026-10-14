package com.portfolio.ledger.api;

import com.portfolio.ledger.batch.DailyBalance;
import com.portfolio.ledger.batch.DailyBalanceRepository;
import com.portfolio.ledger.batch.NightlyCloseResult;
import com.portfolio.ledger.batch.NightlyCloseService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Nightly close: trigger it (ADMIN only, see SecurityConfig) and read the end-of-day snapshots. */
@RestController
@RequestMapping("/api/v1")
class BatchController {

    record CloseBody(LocalDate businessDate, Boolean rerun) { }

    private final NightlyCloseService closeService;
    private final DailyBalanceRepository balances;
    private final AccountAccess access;

    BatchController(NightlyCloseService closeService, DailyBalanceRepository balances, AccountAccess access) {
        this.closeService = closeService;
        this.balances = balances;
        this.access = access;
    }

    /** Runs the close for one UTC day and returns when it has finished. 409 if the day is already closed. */
    @PostMapping("/admin/nightly-close")
    NightlyCloseResult close(@RequestBody CloseBody body) {
        return closeService.run(body.businessDate(), Boolean.TRUE.equals(body.rerun()));
    }

    /** Newest day first. Same ownership rule as the other account endpoints. */
    @GetMapping("/accounts/{id}/daily-balances")
    List<DailyBalance> dailyBalances(Authentication auth, @PathVariable UUID id,
                                     @RequestParam(defaultValue = "30") int limit) {
        access.require(auth, id);
        return balances.findRecent(id, Math.max(1, Math.min(limit, 365)));
    }
}
