package com.portfolio.ledger.api;

import com.portfolio.ledger.domain.AccountNotFoundException;
import com.portfolio.ledger.service.LedgerService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Ownership rule: a user may act only on accounts they own; an ADMIN may act on any account.
 * For someone else's account the answer is "not found", the same as for an account that does not exist,
 * so the API does not reveal which account ids exist.
 */
@Component
class AccountAccess {

    private final LedgerService ledger;

    AccountAccess(LedgerService ledger) {
        this.ledger = ledger;
    }

    void require(Authentication auth, UUID accountId) {
        if (isAdmin(auth)) {
            return;
        }
        if (!ledger.isOwnedBy(accountId, auth.getName())) {
            throw new AccountNotFoundException(accountId);
        }
    }

    static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
