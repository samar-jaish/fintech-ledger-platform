package com.portfolio.ledger.service;

import com.portfolio.ledger.domain.*;
import com.portfolio.ledger.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure business-logic tests: the DB is mocked. Concurrency lives in the Testcontainers tests. */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock LedgerRepository repo;
    LedgerService service;

    final UUID aId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID bId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    final UUID txId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LedgerService(repo);
    }

    private static Account acct(UUID id, String cur, String balance, boolean allowNegative) {
        return new Account(id, "owner", cur, new BigDecimal(balance), allowNegative);
    }

    private TransferRequest req(String amount) {
        return new TransferRequest("key-1", aId, bId, new BigDecimal(amount), "test");
    }

    @Test
    void transfer_writesBalancedEntriesAndUpdatesBalances() {
        when(repo.insertTransaction(eq("key-1"), eq("test"), any())).thenReturn(Optional.of(txId));
        when(repo.lockAccounts(anyCollection()))
                .thenReturn(List.of(acct(aId, "USD", "1000.00", false), acct(bId, "USD", "0.00", false)));

        TransferResult result = service.transfer(req("100.00"));

        assertFalse(result.replayed());
        verify(repo).insertEntry(txId, aId, Direction.DEBIT, new BigDecimal("100.00"));
        verify(repo).insertEntry(txId, bId, Direction.CREDIT, new BigDecimal("100.00"));
        verify(repo).updateBalance(aId, new BigDecimal("900.00"));
        verify(repo).updateBalance(bId, new BigDecimal("100.00"));
    }

    @Test
    void transfer_insufficientFunds_throwsAndWritesNothing() {
        when(repo.insertTransaction(any(), any(), any())).thenReturn(Optional.of(txId));
        when(repo.lockAccounts(anyCollection()))
                .thenReturn(List.of(acct(aId, "USD", "50.00", false), acct(bId, "USD", "0.00", false)));

        assertThrows(InsufficientFundsException.class, () -> service.transfer(req("100.00")));

        verify(repo, never()).insertEntry(any(), any(), any(), any());
        verify(repo, never()).updateBalance(any(), any());
    }

    @Test
    void transfer_systemAccountMayGoNegative() {
        when(repo.insertTransaction(any(), any(), any())).thenReturn(Optional.of(txId));
        when(repo.lockAccounts(anyCollection()))
                .thenReturn(List.of(acct(aId, "USD", "0.00", true), acct(bId, "USD", "0.00", false)));

        service.transfer(req("100.00"));

        verify(repo).updateBalance(aId, new BigDecimal("-100.00"));
    }

    @Test
    void transfer_replayedKey_returnsOriginalAndTouchesNoAccounts() {
        TransferRequest request = req("100.00");
        when(repo.insertTransaction(any(), any(), any())).thenReturn(Optional.empty());
        when(repo.findTransactionByKey("key-1")).thenReturn(new StoredTransaction(txId, request.fingerprint()));

        TransferResult result = service.transfer(request);

        assertTrue(result.replayed());
        assertEquals(txId, result.transactionId());
        verify(repo, never()).lockAccounts(any());
    }

    @Test
    void transfer_replayedKey_withDifferentPayload_isRejected() {
        TransferRequest original = req("100.00");
        TransferRequest tampered = req("999.00");
        when(repo.insertTransaction(any(), any(), any())).thenReturn(Optional.empty());
        when(repo.findTransactionByKey("key-1")).thenReturn(new StoredTransaction(txId, original.fingerprint()));

        assertThrows(IdempotencyKeyReuseException.class, () -> service.transfer(tampered));

        verify(repo, never()).lockAccounts(any());
    }

    @Test
    void transfer_currencyMismatch_rejected() {
        when(repo.insertTransaction(any(), any(), any())).thenReturn(Optional.of(txId));
        when(repo.lockAccounts(anyCollection()))
                .thenReturn(List.of(acct(aId, "USD", "100.00", false), acct(bId, "EUR", "0.00", false)));

        assertThrows(IllegalArgumentException.class, () -> service.transfer(req("10.00")));
        verify(repo, never()).insertEntry(any(), any(), any(), any());
    }

    @Test
    void transfer_unknownAccount_rejected() {
        when(repo.insertTransaction(any(), any(), any())).thenReturn(Optional.of(txId));
        when(repo.lockAccounts(anyCollection())).thenReturn(List.of(acct(aId, "USD", "100.00", false)));

        assertThrows(AccountNotFoundException.class, () -> service.transfer(req("10.00")));
    }

    @Test
    void request_validation() {
        assertThrows(IllegalArgumentException.class, () -> req("0"));
        assertThrows(IllegalArgumentException.class, () -> req("-5"));
        assertThrows(IllegalArgumentException.class, () -> req("1.23456"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferRequest("k", aId, aId, BigDecimal.TEN, "same account"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferRequest(" ", aId, bId, BigDecimal.TEN, "blank key"));
    }

    @Test
    void fingerprint_ignoresScaleButNotAmount() {
        assertEquals(req("100").fingerprint(), req("100.00").fingerprint());
        assertNotEquals(req("100").fingerprint(), req("101").fingerprint());
    }

    @Test
    void getStatement_rejectsInvalidLimit() {
        assertThrows(IllegalArgumentException.class, () -> service.getStatement(aId, 0));
        assertThrows(IllegalArgumentException.class, () -> service.getStatement(aId, 1001));
    }
}
