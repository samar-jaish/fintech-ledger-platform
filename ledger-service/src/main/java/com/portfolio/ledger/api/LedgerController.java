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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Accounts, deposits, transfers, statements and reconciliation. */
@RestController
@RequestMapping("/api/v1")
class LedgerController {

    private final LedgerService ledger;
    private final AccountAccess access;

    LedgerController(LedgerService ledger, AccountAccess access) {
        this.ledger = ledger;
        this.access = access;
    }

    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> createAccount(Authentication auth, @RequestBody CreateAccountBody body) {
        body.validate();
        Account account = ledger.openAccountFor(auth.getName(), body.ownerName(), body.currency());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id()))
                .body(AccountResponse.from(account));
    }

    /** The signed-in user's own accounts (an admin also sees only their own here; other accounts are fetched by id). */
    @GetMapping("/accounts")
    List<AccountResponse> myAccounts(Authentication auth) {
        return ledger.getAccountsOwnedBy(auth.getName()).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/accounts/{id}")
    AccountResponse getAccount(Authentication auth, @PathVariable UUID id) {
        access.require(auth, id);
        return AccountResponse.from(ledger.getAccount(id));
    }

    /** Adds money from the house account. Simulated cash-in: there are no real payment rails. */
    @PostMapping("/accounts/{id}/deposits")
    ResponseEntity<TransferResponse> deposit(Authentication auth, @PathVariable UUID id,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @RequestBody DepositBody body) {
        access.require(auth, id);
        TransferRequest request = new TransferRequest("deposit:" + ApiDtos.key(idempotencyKey),
                SystemAccounts.HOUSE_USD, id, ApiDtos.required(body.amount(), "amount"), "deposit");
        return transferResponse(ledger.transfer(request));
    }

    @PostMapping("/transfers")
    ResponseEntity<TransferResponse> transfer(Authentication auth,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestBody TransferBody body) {
        TransferRequest request = body.toRequest(idempotencyKey);
        access.require(auth, request.fromAccountId());
        return transferResponse(ledger.transfer(request));
    }

    @GetMapping("/accounts/{id}/statement")
    List<LedgerEntry> statement(Authentication auth, @PathVariable UUID id,
                                @RequestParam(defaultValue = "50") int limit) {
        access.require(auth, id);
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
