package com.portfolio.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ledger.domain.TransferRequest;

import java.math.BigDecimal;
import java.util.UUID;

/** The event written to the outbox when a transfer commits. This JSON is the contract other services read. */
public record TransferCompleted(UUID transactionId, UUID fromAccountId, UUID toAccountId,
                                BigDecimal amount, String description) {

    public static final String AGGREGATE = "transfer";
    public static final String TYPE = "TransferCompleted";

    private static final ObjectMapper JSON = new ObjectMapper();

    public static TransferCompleted of(UUID transactionId, TransferRequest req) {
        return new TransferCompleted(transactionId, req.fromAccountId(), req.toAccountId(), req.amount(), req.description());
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + TYPE, e);
        }
    }
}
