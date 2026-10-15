package com.portfolio.trading;

import com.portfolio.trading.ledger.InsufficientFundsException;
import com.portfolio.trading.ledger.LedgerClient;
import com.portfolio.trading.ledger.LedgerRejectedException;
import com.portfolio.trading.ledger.LedgerUnavailableException;
import com.portfolio.trading.market.MarketDataProvider;
import com.portfolio.trading.market.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes market orders as a saga. Cash lives in the ledger service and positions live here, and two databases
 * cannot commit together, so a trade is a sequence of steps with a stored state instead of one transaction.
 *
 *   BUY  : 1. save trade PENDING            2. ledger: customer -> exchange   3. add shares, mark COMPLETED
 *   SELL : 1. save trade PENDING and take the shares out of the position (reserve them)
 *          2. ledger: exchange -> customer  3. mark COMPLETED
 *
 * Failure handling:
 *  - The ledger says no (no funds, no account, refused): the trade is REJECTED. For a sell, the reserved shares are
 *    returned (the compensating step). Nothing moved in the ledger, so there is nothing else to undo.
 *  - The ledger cannot be reached or does not answer: the trade stays PENDING. The transfer may or may not have
 *    happened, so it is retried with the SAME ledger idempotency key (trade:<id>). The ledger applies it once, so
 *    retrying is always safe. A client retrying with the same key, and the recovery job, both do this.
 *  - Every state change is a conditional update (only from PENDING), so two actors finishing the same trade at once
 *    cannot add the shares twice.
 *
 * The price is fetched before anything is stored, so no database work happens during a network call to the market.
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    static final String FUNDS = "INSUFFICIENT_FUNDS";
    static final String HOLDINGS = "INSUFFICIENT_HOLDINGS";
    static final String LEDGER_REJECTED = "LEDGER_REJECTED";

    private final MarketDataProvider market;
    private final LedgerClient ledger;
    private final TradingRepository trades;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TradeService(MarketDataProvider market, LedgerClient ledger, TradingRepository trades,
                        PlatformTransactionManager txManager, Clock clock) {
        this.market = market;
        this.ledger = ledger;
        this.trades = trades;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * Runs a trade. Returns COMPLETED, or PENDING if the ledger was unreachable (it is retried automatically).
     * Throws TradeRejectedException when the trade is refused.
     */
    public TradeResult execute(TradeRequest req) {
        Optional<Trade> existing = trades.findTradeByKey(req.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), req);
        }
        Quote quote = market.getQuote(req.symbol());
        UUID exchangeId = ledger.exchangeAccountId(quote.currency());
        BigDecimal notional = quote.price().multiply(req.quantity()).setScale(4, RoundingMode.HALF_EVEN);
        if (notional.signum() <= 0) {
            throw new IllegalArgumentException("Trade value rounds to zero; quantity is too small");
        }
        Trade pending = new Trade(UUID.randomUUID(), req.idempotencyKey(), req.fingerprint(), req.accountId(), exchangeId,
                req.symbol(), req.side(), req.quantity(), quote.price(), notional, quote.currency(), quote.source(),
                quote.asOf(), clock.instant(), TradeStatus.PENDING, null);

        Started started = tx.execute(status -> start(pending));
        if (!started.created()) {
            return replay(started.trade(), req);   // a concurrent request with the same key won
        }
        return toResult(drive(started.trade()), false);
    }

    /**
     * Pushes a trade forward: asks the ledger to move the cash (safe to repeat) and records the outcome.
     * Returns the trade as stored afterwards. Used by execute, by retries with the same key, and by TradeRecovery.
     */
    public Trade drive(Trade trade) {
        if (trade.status() != TradeStatus.PENDING) {
            return trade;
        }
        boolean buy = trade.side() == Side.BUY;
        UUID from = buy ? trade.accountId() : trade.exchangeAccountId();
        UUID to = buy ? trade.exchangeAccountId() : trade.accountId();
        try {
            ledger.transfer("trade:" + trade.id(), from, to, trade.notional(), describe(trade));
        } catch (InsufficientFundsException e) {
            return reject(trade, FUNDS);
        } catch (AccountNotFoundException | LedgerRejectedException e) {
            return reject(trade, LEDGER_REJECTED);
        } catch (LedgerUnavailableException e) {
            log.warn("Trade {} stays PENDING, ledger not confirmed: {}", trade.id(), e.getMessage());
            return trade;
        }
        return complete(trade);
    }

    private Started start(Trade trade) {
        if (!trades.insertPending(trade)) {
            return new Started(trades.findTradeByKey(trade.idempotencyKey()).orElseThrow(), false);
        }
        if (trade.side() == Side.SELL
                && !trades.removeFromHolding(trade.accountId(), trade.symbol(), trade.quantity())) {
            trades.markRejected(trade.id(), HOLDINGS, clock.instant());   // nothing was reserved, nothing to give back
            return new Started(trades.findTradeById(trade.id()).orElseThrow(), true);
        }
        return new Started(trade, true);
    }

    private Trade complete(Trade trade) {
        tx.executeWithoutResult(status -> {
            if (trades.markCompleted(trade.id(), clock.instant()) && trade.side() == Side.BUY) {
                trades.addToHolding(trade.accountId(), trade.symbol(), trade.quantity(), trade.price());
            }
        });
        return trades.findTradeById(trade.id()).orElseThrow();
    }

    private Trade reject(Trade trade, String reason) {
        tx.executeWithoutResult(status -> {
            if (trades.markRejected(trade.id(), reason, clock.instant()) && trade.side() == Side.SELL) {
                trades.restoreHolding(trade.accountId(), trade.symbol(), trade.quantity());   // compensation
            }
        });
        return trades.findTradeById(trade.id()).orElseThrow();
    }

    private TradeResult replay(Trade stored, TradeRequest req) {
        if (!stored.requestHash().equals(req.fingerprint())) {
            throw new IdempotencyKeyReuseException(req.idempotencyKey());
        }
        return toResult(drive(stored), true);   // a retry also nudges a PENDING trade forward
    }

    private static TradeResult toResult(Trade trade, boolean replayed) {
        if (trade.status() == TradeStatus.REJECTED) {
            throw new TradeRejectedException(trade.rejectReason(), "Trade " + trade.id() + " was rejected: " + trade.rejectReason());
        }
        return new TradeResult(trade, replayed);
    }

    private static String describe(Trade t) {
        return t.side() + " " + t.quantity().stripTrailingZeros().toPlainString() + " " + t.symbol();
    }

    private record Started(Trade trade, boolean created) { }
}
