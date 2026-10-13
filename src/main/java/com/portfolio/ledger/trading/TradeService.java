package com.portfolio.ledger.trading;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.AccountNotFoundException;
import com.portfolio.ledger.domain.IdempotencyKeyReuseException;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.market.MarketDataProvider;
import com.portfolio.ledger.market.Quote;
import com.portfolio.ledger.service.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes market orders at the current price from the active MarketDataProvider.
 *
 * Money moves through the ledger (customer cash against a house EXCHANGE account), so every trade is a
 * balanced, auditable double-entry transfer. Holdings are updated in the same database transaction:
 * the cash movement, the position change and the trade record commit together or not at all.
 *
 * Design points:
 * - The price is fetched BEFORE the transaction starts, so no database locks are held during a network call.
 * - Replays are answered from the stored trade without asking the market for a price.
 * - Lock order is always: trade key (unique index), exchange-account advisory lock, ledger account rows (in id
 *   order), then the holdings row. One order everywhere prevents deadlocks between a buy and a sell by the
 *   same account.
 * - Every trade locks the EXCHANGE account row until commit, so trades are serialized on that one row. That is
 *   the same hot-row effect measured for the house account in the benchmark. The advisory lock only guards
 *   first-time creation of the exchange account and adds no serialization beyond that. Sharding the exchange
 *   account is the usual fix for throughput.
 * - A sell moves the cash before it checks the holding; if the holding is too small the exception rolls the
 *   whole transaction back, including the ledger entries.
 */
@Service
public class TradeService {

    private final MarketDataProvider market;
    private final LedgerService ledger;
    private final TradingRepository trades;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TradeService(MarketDataProvider market, LedgerService ledger, TradingRepository trades,
                        PlatformTransactionManager txManager, Clock clock) {
        this.market = market;
        this.ledger = ledger;
        this.trades = trades;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public TradeResult execute(TradeRequest req) {
        Optional<Trade> existing = trades.findTradeByKey(req.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), req);
        }
        Quote quote = market.getQuote(req.symbol());
        return tx.execute(status -> executeInTransaction(req, quote));
    }

    private TradeResult executeInTransaction(TradeRequest req, Quote quote) {
        if (!trades.accountExists(req.accountId())) {
            throw new AccountNotFoundException(req.accountId());
        }
        BigDecimal notional = quote.price().multiply(req.quantity()).setScale(4, RoundingMode.HALF_EVEN);
        if (notional.signum() <= 0) {
            throw new IllegalArgumentException("Trade value rounds to zero; quantity is too small");
        }
        Trade trade = new Trade(UUID.randomUUID(), req.idempotencyKey(), req.fingerprint(), req.accountId(),
                req.symbol(), req.side(), req.quantity(), quote.price(), notional, quote.currency(),
                quote.source(), quote.asOf(), clock.instant());

        if (!trades.insertTrade(trade)) {
            // A concurrent request with the same key committed first.
            Trade stored = trades.findTradeByKey(req.idempotencyKey()).orElseThrow();
            return replay(stored, req);
        }

        Account exchange = trades.findOrCreateExchangeAccount(quote.currency());
        String ledgerKey = "trade:" + req.idempotencyKey();
        String description = req.side() + " " + req.quantity().stripTrailingZeros().toPlainString() + " " + req.symbol();
        if (req.side() == Side.BUY) {
            ledger.transfer(new TransferRequest(ledgerKey, req.accountId(), exchange.id(), notional, description));
            trades.addToHolding(req.accountId(), req.symbol(), req.quantity(), quote.price());
        } else {
            ledger.transfer(new TransferRequest(ledgerKey, exchange.id(), req.accountId(), notional, description));
            if (!trades.removeFromHolding(req.accountId(), req.symbol(), req.quantity())) {
                throw new InsufficientHoldingsException(req.accountId(), req.symbol());
            }
        }
        return new TradeResult(trade, false);
    }

    private TradeResult replay(Trade stored, TradeRequest req) {
        if (!stored.requestHash().equals(req.fingerprint())) {
            throw new IdempotencyKeyReuseException(req.idempotencyKey());
        }
        return new TradeResult(stored, true);
    }
}
