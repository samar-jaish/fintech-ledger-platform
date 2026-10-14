package com.portfolio.ledger.api;

import com.portfolio.ledger.api.ApiDtos.TradeBody;
import com.portfolio.ledger.api.ApiDtos.TradeResponse;
import com.portfolio.ledger.service.LedgerService;
import com.portfolio.ledger.trading.Portfolio;
import com.portfolio.ledger.trading.PortfolioService;
import com.portfolio.ledger.trading.TradeRequest;
import com.portfolio.ledger.trading.TradeResult;
import com.portfolio.ledger.trading.TradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Market orders, trade history and portfolio valuation. */
@RestController
@RequestMapping("/api/v1")
class TradingController {

    private final TradeService trading;
    private final PortfolioService portfolio;
    private final LedgerService ledger;
    private final AccountAccess access;

    TradingController(TradeService trading, PortfolioService portfolio, LedgerService ledger, AccountAccess access) {
        this.trading = trading;
        this.portfolio = portfolio;
        this.ledger = ledger;
        this.access = access;
    }

    /** 201 for a new trade, 200 when the idempotency key was already processed. */
    @PostMapping("/trades")
    ResponseEntity<TradeResponse> trade(Authentication auth, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestBody TradeBody body) {
        TradeRequest request = body.toRequest(idempotencyKey);
        access.require(auth, request.accountId());
        TradeResult result = trading.execute(request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TradeResponse.from(result.trade(), result.replayed()));
    }

    @GetMapping("/accounts/{id}/portfolio")
    Portfolio portfolio(Authentication auth, @PathVariable UUID id) {
        access.require(auth, id);
        return portfolio.getPortfolio(id);
    }

    @GetMapping("/accounts/{id}/trades")
    List<TradeResponse> trades(Authentication auth, @PathVariable UUID id,
                               @RequestParam(defaultValue = "50") int limit) {
        access.require(auth, id);
        ledger.getAccount(id);
        return portfolio.getTrades(id, limit).stream().map(t -> TradeResponse.from(t, false)).toList();
    }
}
