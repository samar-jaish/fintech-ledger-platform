package com.portfolio.ledger.domain;

public record VersionedAccount(Account account, long version) { }
