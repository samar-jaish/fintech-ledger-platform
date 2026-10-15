package com.portfolio.trading.api;

import com.portfolio.trading.ledger.LedgerClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Decides whether the caller may use an account. Ownership data lives in the ledger service, so this service does not
 * keep a copy that could go stale: it asks the ledger to read the account with the caller's own token. The ledger
 * answers 404 for someone else's account, which surfaces here as AccountNotFoundException.
 */
@Component
class AccountAccess {

    private final LedgerClient ledger;

    AccountAccess(LedgerClient ledger) {
        this.ledger = ledger;
    }

    void require(Authentication auth, UUID accountId) {
        ledger.getAccount(accountId, bearerToken(auth));
    }

    /** The caller's raw JWT, to pass on to the ledger. Null when the authentication is not a JWT (only in tests). */
    static String bearerToken(Authentication auth) {
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : null;
    }
}
