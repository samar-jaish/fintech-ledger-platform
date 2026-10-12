package com.portfolio.ledger.domain;

import java.util.UUID;

public record StoredTransaction(UUID id, String requestHash) { }
