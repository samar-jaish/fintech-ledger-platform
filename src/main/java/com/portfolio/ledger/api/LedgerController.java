package com.portfolio.ledger.api;

import com.portfolio.ledger.api.ApiDtos.*;
import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.LedgerEntry;
import com.portfolio.ledger.domain.SystemAccounts;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.domain.TransferResult;
import com.portfolio.ledger.service.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Accounts, deposits, transfers, statements and reconciliation. */
@RestController
@RequestMapping("/api/v1")
class LedgerController {

    private final LedgerService ledger;

    LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> createAccount(@RequestBody CreateAccountBody body) {
        body.validate();
        Account account = ledger.openAccount(body.ownerName(), body.currency());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/accounts/{id}")
    AccountResponse getAccount(@PathVariable UUID id) {
        return AccountResponse.from(ledger.getAccount(id));
    }

    /** Adds money from the house account. Simulated cash-in: there are no real payment rails. */
    @PostMapping("/accounts/{id}/deposits")
    ResponseEntity<TransferResponse> deposit(@PathVariable UUID id,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @RequestBody DepositBody body) {
        TransferRequest request = new TransferRequest("deposit:" + ApiDtos.key(idempotencyKey),
                SystemAccounts.HOUSE_USD, id, ApiDtos.required(body.amount(), "amount"), "deposit");
        return transferResponse(ledger.transfer(request));
    }

    @PostMapping("/transfers")
    ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestBody TransferBody body) {
        return transferResponse(ledger.transfer(body.toRequest(idempotencyKey)));
    }

    @GetMapping("/accounts/{id}/statement")
    List<LedgerEntry> statement(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        ledger.getAccount(id);
        return ledger.getStatement(id, limit);
    }

    @GetMapping("/reconciliation")
    ReconciliationResponse reconciliation() {
        var mismatches = ledger.reconcile();
        return new ReconciliationResponse(mismatches.isEmpty(), mismatches);
    }

    /** 201 for a new transfer, 200 when the idempotency key was already processed. */
    private static ResponseEntity<TransferResponse> transferResponse(TransferResult result) {
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new TransferResponse(result.transactionId(), result.replayed()));
    }
}
