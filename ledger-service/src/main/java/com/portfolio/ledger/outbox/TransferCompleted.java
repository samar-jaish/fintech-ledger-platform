package com.portfolio.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ledger.domain.TransferRequest;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The event written to the outbox when a transfer commits. This JSON is the contract other services read.
 * fromOwner and toOwner are the identity provider user ids (JWT "sub") of the two accounts' owners, or null for a house
 * account. They let a consumer decide who an event concerns without calling this service back (event-carried state).
 */
public record TransferCompleted(UUID transactionId, UUID fromAccountId, UUID toAccountId,
                                BigDecimal amount, String description, String fromOwner, String toOwner) {

    public static final String AGGREGATE = "transfer";
    public static final String TYPE = "TransferCompleted";

    private static final ObjectMapper JSON = new ObjectMapper();

    public static TransferCompleted of(UUID transactionId, TransferRequest req) {
        return of(transactionId, req, Map.of());
    }

    /** owners maps an account id to its owner's subject; accounts missing from it have no owner. */
    public static TransferCompleted of(UUID transactionId, TransferRequest req, Map<UUID, String> owners) {
        return new TransferCompleted(transactionId, req.fromAccountId(), req.toAccountId(), req.amount(),
                req.description(), owners.get(req.fromAccountId()), owners.get(req.toAccountId()));
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + TYPE, e);
        }
    }
}
