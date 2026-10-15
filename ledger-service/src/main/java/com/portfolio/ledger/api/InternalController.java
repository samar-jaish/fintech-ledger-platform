package com.portfolio.ledger.api;

import com.portfolio.ledger.api.ApiDtos.AccountResponse;
import com.portfolio.ledger.api.ApiDtos.TransferBody;
import com.portfolio.ledger.api.ApiDtos.TransferResponse;
import com.portfolio.ledger.domain.TransferResult;
import com.portfolio.ledger.service.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints for other services, not for end users (SecurityConfig requires the SERVICE role). A service such as
 * trading-service acts on behalf of a customer but is not the account owner, so these skip the ownership check;
 * the calling service is trusted to have checked who its customer is.
 */
@RestController
@RequestMapping("/api/v1/internal")
class InternalController {

    static final String EXCHANGE_NAME = "EXCHANGE";

    record ExchangeAccountBody(String currency) { }

    private final LedgerService ledger;

    InternalController(LedgerService ledger) {
        this.ledger = ledger;
    }

    /** Move money between any two accounts. Idempotent on the Idempotency-Key, like the public transfer. */
    @PostMapping("/transfers")
    ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestBody TransferBody body) {
        TransferResult result = ledger.transfer(body.toRequest(idempotencyKey));
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new TransferResponse(result.transactionId(), result.replayed()));
    }

    /** The house account that is the counterparty of every trade in this currency; created on first request. */
    @PostMapping("/exchange-accounts")
    AccountResponse exchangeAccount(@RequestBody ExchangeAccountBody body) {
        ApiDtos.CURRENCY_CHECK.accept(body.currency());
        return AccountResponse.from(ledger.findOrCreateSystemAccount(EXCHANGE_NAME, body.currency()));
    }
}
