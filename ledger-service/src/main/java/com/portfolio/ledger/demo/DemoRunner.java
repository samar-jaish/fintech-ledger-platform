package com.portfolio.ledger.demo;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.BalanceMismatch;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.market.MarketDataException;
import com.portfolio.ledger.market.MarketDataProvider;
import com.portfolio.ledger.market.PriceBackfillService;
import com.portfolio.ledger.service.LedgerService;
import com.portfolio.ledger.trading.Portfolio;
import com.portfolio.ledger.trading.PortfolioPosition;
import com.portfolio.ledger.trading.PortfolioService;
import com.portfolio.ledger.trading.Side;
import com.portfolio.ledger.trading.TradeRequest;
import com.portfolio.ledger.trading.TradeResult;
import com.portfolio.ledger.trading.TradeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Console demo, active only with the "demo" profile. It opens an account, deposits cash, records recent
 * price history, trades a few stocks and prints the valued portfolio with the source of every price.
 *
 * Run it against any provider: set MARKET_PROVIDER to simulated, replay or live.
 * It writes to the configured database, so use the local docker-compose one.
 */
@Component
@Profile("demo")
public class DemoRunner implements CommandLineRunner {

    private static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "NVDA");
    private static final int HISTORY_DAYS = 45;

    private final MarketDataProvider market;
    private final LedgerService ledger;
    private final TradeService trading;
    private final PortfolioService portfolio;
    private final PriceBackfillService backfill;
    private final Clock clock;

    public DemoRunner(MarketDataProvider market, LedgerService ledger, TradeService trading,
                      PortfolioService portfolio, PriceBackfillService backfill, Clock clock) {
        this.market = market;
        this.ledger = ledger;
        this.trading = trading;
        this.portfolio = portfolio;
        this.backfill = backfill;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        String mode = market.name();
        System.out.println();
        System.out.println("=== Ledger Platform demo | market data provider: " + mode.toUpperCase() + " ===");
        System.out.println(banner(mode));

        try {
            String runId = UUID.randomUUID().toString().substring(0, 8);
            Account house = ledger.openSystemAccount("DEMO-HOUSE-" + runId, "USD");
            Account investor = ledger.openAccount("Demo Investor " + runId, "USD");
            ledger.transfer(new TransferRequest("demo-deposit-" + runId, house.id(), investor.id(),
                    new BigDecimal("10000.00"), "initial deposit"));
            System.out.println("Opened account " + investor.id() + " with 10,000.00 USD");

            if (!"replay".equals(mode)) {
                LocalDate today = LocalDate.now(clock);
                int stored = backfill.backfill(SYMBOLS, today.minusDays(HISTORY_DAYS), today);
                System.out.println("Recorded " + stored + " new daily closes into price_history (existing days are skipped)");
            } else {
                System.out.println("Replay mode: using closes already recorded in price_history");
            }

            trade(runId, investor, "AAPL", Side.BUY, "10");
            trade(runId, investor, "MSFT", Side.BUY, "5");
            trade(runId, investor, "NVDA", Side.BUY, "3");
            trade(runId, investor, "AAPL", Side.SELL, "2");

            print(portfolio.getPortfolio(investor.id()));

            List<BalanceMismatch> drift = ledger.reconcile();
            System.out.println(drift.isEmpty()
                    ? "Reconciliation: ledger balances match their entries."
                    : "Reconciliation FAILED for " + drift.size() + " account(s).");
        } catch (MarketDataException e) {
            System.out.println();
            System.out.println("Market data problem: " + e.getMessage());
            System.out.println("Tip: set MARKET_PROVIDER=simulated to run without any network or keys.");
        }
        System.out.println();
    }

    private void trade(String runId, Account investor, String symbol, Side side, String quantity) {
        TradeResult result = trading.execute(new TradeRequest(
                "demo-" + runId + "-" + side + "-" + symbol, investor.id(), symbol, side, new BigDecimal(quantity)));
        System.out.printf("%-4s %6s %-5s @ %10s  value %12s  (price source: %s)%n",
                side, quantity, symbol, result.trade().price(), result.trade().notional(), result.trade().priceSource());
    }

    private static void print(Portfolio p) {
        System.out.println();
        System.out.printf("%-6s %9s %10s %12s %10s %12s %12s  %s%n",
                "SYMBOL", "QTY", "AVG COST", "COST BASIS", "PRICE", "VALUE", "P&L", "PRICE SOURCE");
        for (PortfolioPosition pos : p.positions()) {
            if (pos.priceAvailable()) {
                System.out.printf("%-6s %9s %10s %12s %10s %12s %12s  %s%n", pos.symbol(),
                        pos.quantity().stripTrailingZeros().toPlainString(), pos.avgCost(), pos.costBasis(),
                        pos.price(), pos.marketValue(), pos.unrealizedPnl(), pos.priceSource());
            } else {
                System.out.printf("%-6s %9s %10s %12s  price unavailable%n", pos.symbol(),
                        pos.quantity().stripTrailingZeros().toPlainString(), pos.avgCost(), pos.costBasis());
            }
        }
        System.out.println();
        System.out.println("Cash:               " + p.cash() + " " + p.currency());
        System.out.println("Positions (market): " + p.totalMarketValue());
        System.out.println("Unrealized P&L:     " + p.totalUnrealizedPnl());
        System.out.println("Total value:        " + p.totalValue() + (p.complete() ? "" : "  (INCOMPLETE: some prices unavailable)"));
    }

    private static String banner(String mode) {
        return switch (mode) {
            case "simulated" -> "*** SIMULATED PRICES: generated numbers, not real market data ***";
            case "replay" -> "Replayed prices recorded earlier in this database (real vendor closes if they came from live mode).";
            case "live" -> "Live prices from Finnhub (quotes) and Twelve Data (history). Free tiers are rate limited.";
            default -> "Unknown provider.";
        };
    }
}
