package com.portfolio.trading.api;

import com.portfolio.trading.Portfolio;
import com.portfolio.trading.PortfolioService;
import com.portfolio.trading.TradeRequest;
import com.portfolio.trading.TradeResult;
import com.portfolio.trading.TradeService;
import com.portfolio.trading.TradeStatus;
import com.portfolio.trading.api.ApiDtos.TradeBody;
import com.portfolio.trading.api.ApiDtos.TradeResponse;
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
    private final AccountAccess access;

    TradingController(TradeService trading, PortfolioService portfolio, AccountAccess access) {
        this.trading = trading;
        this.portfolio = portfolio;
        this.access = access;
    }

    /**
     * 201 for a new completed trade, 200 when the idempotency key was already processed, 202 when the trade is accepted
     * but the ledger has not confirmed the cash yet (status PENDING: repeat the same request later to see the outcome),
     * 422 when the trade was rejected.
     */
    @PostMapping("/trades")
    ResponseEntity<TradeResponse> trade(Authentication auth, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestBody TradeBody body) {
        TradeRequest request = body.toRequest(idempotencyKey);
        access.require(auth, request.accountId());
        TradeResult result = trading.execute(request);
        HttpStatus status = result.trade().status() == TradeStatus.PENDING ? HttpStatus.ACCEPTED
                : result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TradeResponse.from(result.trade(), result.replayed()));
    }

    @GetMapping("/accounts/{id}/portfolio")
    Portfolio portfolio(Authentication auth, @PathVariable UUID id) {
        access.require(auth, id);
        return portfolio.getPortfolio(id, AccountAccess.bearerToken(auth));
    }

    @GetMapping("/accounts/{id}/trades")
    List<TradeResponse> trades(Authentication auth, @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        access.require(auth, id);
        return portfolio.getTrades(id, limit).stream().map(t -> TradeResponse.from(t, false)).toList();
    }
}
