package com.portfolio.ledger.api;

import com.portfolio.ledger.api.ApiDtos.TradeBody;
import com.portfolio.ledger.api.ApiDtos.TradeResponse;
import com.portfolio.ledger.service.LedgerService;
import com.portfolio.ledger.trading.Portfolio;
import com.portfolio.ledger.trading.PortfolioService;
import com.portfolio.ledger.trading.TradeResult;
import com.portfolio.ledger.trading.TradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    TradingController(TradeService trading, PortfolioService portfolio, LedgerService ledger) {
        this.trading = trading;
        this.portfolio = portfolio;
        this.ledger = ledger;
    }

    /** 201 for a new trade, 200 when the idempotency key was already processed. */
    @PostMapping("/trades")
    ResponseEntity<TradeResponse> trade(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestBody TradeBody body) {
        TradeResult result = trading.execute(body.toRequest(idempotencyKey));
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TradeResponse.from(result.trade(), result.replayed()));
    }

    @GetMapping("/accounts/{id}/portfolio")
    Portfolio portfolio(@PathVariable UUID id) {
        return portfolio.getPortfolio(id);
    }

    @GetMapping("/accounts/{id}/trades")
    List<TradeResponse> trades(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        ledger.getAccount(id);
        return portfolio.getTrades(id, limit).stream().map(t -> TradeResponse.from(t, false)).toList();
    }
}
