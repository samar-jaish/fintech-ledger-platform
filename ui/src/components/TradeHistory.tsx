import { useQuery } from '@tanstack/react-query';
import { getTrades } from '../api/trading';
import { useAuth } from '../auth/AuthContext';
import { formatDateTime, formatMoney, formatQuantity } from '../format';
import { ErrorNote } from './ErrorNote';

const STATUS_LABEL = { COMPLETED: 'Completed', PENDING: 'Waiting for ledger', REJECTED: 'Rejected' } as const;

export function TradeHistory({ accountId }: { accountId: string }) {
  const { getToken } = useAuth();
  const trades = useQuery({
    queryKey: ['trades', accountId],
    queryFn: () => getTrades(getToken(), accountId),
  });

  if (trades.isPending) return <p className="muted">Loading trades…</p>;
  if (trades.isError) return <ErrorNote error={trades.error} />;
  if (trades.data.length === 0) return <p className="muted">No trades yet.</p>;

  return (
    <div className="table-wrap">
      <table>
        <caption className="sr-only">Trade history, newest first</caption>
        <thead>
          <tr>
            <th>When</th>
            <th>Trade</th>
            <th className="num">Price</th>
            <th className="num">Total</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {trades.data.map((t) => (
            <tr key={t.id}>
              <td>{formatDateTime(t.createdAt)}</td>
              <td>
                {t.side === 'BUY' ? 'Buy' : 'Sell'} {formatQuantity(t.quantity)} {t.symbol}
              </td>
              <td className="num">{formatMoney(t.price, t.currency)}</td>
              <td className="num">{formatMoney(t.notional, t.currency)}</td>
              <td>
                <span className={`pill pill-${t.status.toLowerCase()}`}>{STATUS_LABEL[t.status]}</span>
                {t.status === 'REJECTED' && t.rejectReason && <span className="muted"> {t.rejectReason.replaceAll('_', ' ').toLowerCase()}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
