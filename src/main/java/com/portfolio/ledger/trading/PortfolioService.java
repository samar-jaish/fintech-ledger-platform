package com.portfolio.ledger.trading;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.AccountNotFoundException;
import com.portfolio.ledger.market.MarketDataException;
import com.portfolio.ledger.market.MarketDataProvider;
import com.portfolio.ledger.market.Quote;
import com.portfolio.ledger.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Values an account's holdings at the latest prices. Each position states where its price came from.
 *
 * Cash and positions are read inside one REPEATABLE READ, read-only transaction, so a trade that commits
 * in between cannot make the two disagree (cash from before the trade, positions from after it).
 * Prices are fetched afterwards, outside any transaction, so no database connection is held during a
 * network call.
 */
@Service
public class PortfolioService {

    private static final int MAX_TRADES = 1000;

    private final TradingRepository trades;
    private final LedgerRepository ledger;
    private final MarketDataProvider market;
    private final TransactionTemplate snapshotTx;

    public PortfolioService(TradingRepository trades, LedgerRepository ledger, MarketDataProvider market,
                            PlatformTransactionManager txManager) {
        this.trades = trades;
        this.ledger = ledger;
        this.market = market;
        this.snapshotTx = new TransactionTemplate(txManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
    }

    public Portfolio getPortfolio(UUID accountId) {
        Snapshot snapshot = snapshotTx.execute(status -> new Snapshot(
                ledger.findAccount(accountId).orElseThrow(() -> new AccountNotFoundException(accountId)),
                trades.findHoldings(accountId)));
        Account account = snapshot.account();

        List<PortfolioPosition> positions = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        boolean complete = true;

        for (Holding h : snapshot.holdings()) {
            BigDecimal costBasis = money(h.quantity().multiply(h.avgCost()));
            try {
                Quote quote = market.getQuote(h.symbol());
                BigDecimal marketValue = money(h.quantity().multiply(quote.price()));
                BigDecimal pnl = marketValue.subtract(costBasis);
                totalValue = totalValue.add(marketValue);
                totalPnl = totalPnl.add(pnl);
                positions.add(new PortfolioPosition(h.symbol(), h.quantity(), h.avgCost(), costBasis, quote.price(),
                        marketValue, pnl, quote.source(), quote.asOf(), true));
            } catch (MarketDataException e) {
                complete = false;
                positions.add(new PortfolioPosition(h.symbol(), h.quantity(), h.avgCost(), costBasis,
                        null, null, null, null, null, false));
            }
        }
        BigDecimal total = account.balance().add(totalValue);
        return new Portfolio(accountId, account.currency(), account.balance(), positions, totalValue, totalPnl,
                total, complete);
    }

    /** Most recent trades for an account, newest first. */
    public List<Trade> getTrades(UUID accountId, int limit) {
        if (limit < 1 || limit > MAX_TRADES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_TRADES);
        }
        return trades.findTrades(accountId, limit);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_EVEN);
    }

    private record Snapshot(Account account, List<Holding> holdings) { }
}
