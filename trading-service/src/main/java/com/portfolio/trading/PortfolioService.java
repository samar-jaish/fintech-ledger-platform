package com.portfolio.trading;

import com.portfolio.trading.ledger.AccountView;
import com.portfolio.trading.ledger.LedgerClient;
import com.portfolio.trading.market.MarketDataException;
import com.portfolio.trading.market.MarketDataProvider;
import com.portfolio.trading.market.Quote;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Values an account's holdings at the latest prices. Each position states where its price came from.
 *
 * Cash is read from the ledger service as the caller (so ownership is enforced there) and positions from this
 * service's database. They are two separate reads, not one snapshot: while a trade is PENDING they can briefly
 * disagree, which is why the result reports pendingTrades.
 */
@Service
public class PortfolioService {

    private static final int MAX_TRADES = 1000;

    private final TradingRepository trades;
    private final LedgerClient ledger;
    private final MarketDataProvider market;

    public PortfolioService(TradingRepository trades, LedgerClient ledger, MarketDataProvider market) {
        this.trades = trades;
        this.ledger = ledger;
        this.market = market;
    }

    public Portfolio getPortfolio(UUID accountId, String bearerToken) {
        AccountView account = ledger.getAccount(accountId, bearerToken);
        List<Holding> holdings = trades.findHoldings(accountId);
        long pending = trades.countPending(accountId);

        List<PortfolioPosition> positions = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        boolean complete = true;

        for (Holding h : holdings) {
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
                total, complete, pending);
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
}
