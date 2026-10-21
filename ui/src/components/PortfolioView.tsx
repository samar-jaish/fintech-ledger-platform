import { useQuery } from '@tanstack/react-query';
import { getPortfolio } from '../api/trading';
import { useAuth } from '../auth/AuthContext';
import { formatMoney, formatQuantity } from '../format';
import { ErrorNote } from './ErrorNote';

export function PortfolioView({ accountId }: { accountId: string }) {
  const { getToken } = useAuth();
  const portfolio = useQuery({
    queryKey: ['portfolio', accountId],
    queryFn: () => getPortfolio(getToken(), accountId),
    // While a trade waits for the ledger, look again every few seconds so the page settles by itself.
    refetchInterval: (query) => ((query.state.data?.pendingTrades ?? 0) > 0 ? 4000 : false),
  });

  if (portfolio.isPending) return <p className="muted">Loading portfolio…</p>;
  if (portfolio.isError) return <ErrorNote error={portfolio.error} />;

  const p = portfolio.data;
  return (
    <div>
      <dl className="stats">
        <div>
          <dt>Cash</dt>
          <dd>{formatMoney(p.cash, p.currency)}</dd>
        </div>
        <div>
          <dt>Holdings</dt>
          <dd>{formatMoney(p.totalMarketValue, p.currency)}</dd>
        </div>
        <div>
          <dt>Total value</dt>
          <dd>{formatMoney(p.totalValue, p.currency)}</dd>
        </div>
        <div>
          <dt>Unrealised profit</dt>
          <dd className={p.totalUnrealizedPnl >= 0 ? 'in' : 'out'}>{formatMoney(p.totalUnrealizedPnl, p.currency)}</dd>
        </div>
      </dl>

      {p.pendingTrades > 0 && (
        <p className="notice" role="status">
          {p.pendingTrades} trade{p.pendingTrades > 1 ? 's are' : ' is'} waiting for the ledger. Cash and holdings may
          disagree for a moment.
        </p>
      )}
      {!p.complete && <p className="notice">Some holdings could not be priced, so the totals are partial.</p>}

      {p.positions.length === 0 ? (
        <p className="muted">No holdings yet. Buy a stock below.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Holdings</caption>
            <thead>
              <tr>
                <th>Symbol</th>
                <th className="num">Quantity</th>
                <th className="num">Avg cost</th>
                <th className="num">Price</th>
                <th className="num">Value</th>
                <th className="num">Profit</th>
              </tr>
            </thead>
            <tbody>
              {p.positions.map((pos) => (
                <tr key={pos.symbol}>
                  <td>{pos.symbol}</td>
                  <td className="num">{formatQuantity(pos.quantity)}</td>
                  <td className="num">{formatMoney(pos.avgCost, p.currency)}</td>
                  <td className="num">{pos.priceAvailable ? formatMoney(pos.price, p.currency) : 'n/a'}</td>
                  <td className="num">{pos.priceAvailable ? formatMoney(pos.marketValue, p.currency) : 'n/a'}</td>
                  <td className={`num ${(pos.unrealizedPnl ?? 0) >= 0 ? 'in' : 'out'}`}>
                    {pos.priceAvailable ? formatMoney(pos.unrealizedPnl, p.currency) : 'n/a'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
