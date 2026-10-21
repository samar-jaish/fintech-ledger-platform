import { useQuery } from '@tanstack/react-query';
import { getStatement } from '../api/ledger';
import { useAuth } from '../auth/AuthContext';
import { formatDateTime, formatMoney, shortId } from '../format';
import { ErrorNote } from './ErrorNote';

export function Statement({ accountId, currency }: { accountId: string; currency: string }) {
  const { getToken } = useAuth();
  const statement = useQuery({
    queryKey: ['statement', accountId],
    queryFn: () => getStatement(getToken(), accountId),
  });

  if (statement.isPending) return <p className="muted">Loading statement…</p>;
  if (statement.isError) return <ErrorNote error={statement.error} />;
  if (statement.data.length === 0) return <p className="muted">No activity yet. Add money to get started.</p>;

  return (
    <div className="table-wrap">
      <table>
        <caption className="sr-only">Statement, newest first</caption>
        <thead>
          <tr>
            <th>When</th>
            <th>Type</th>
            <th className="num">Amount</th>
            <th>Transaction</th>
          </tr>
        </thead>
        <tbody>
          {statement.data.map((entry) => (
            <tr key={entry.id}>
              <td>{formatDateTime(entry.createdAt)}</td>
              <td>{entry.direction === 'CREDIT' ? 'Money in' : 'Money out'}</td>
              <td className={`num ${entry.direction === 'CREDIT' ? 'in' : 'out'}`}>
                {entry.direction === 'CREDIT' ? '+' : '−'}
                {formatMoney(entry.amount, currency)}
              </td>
              <td>
                <code title={entry.transactionId}>{shortId(entry.transactionId)}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
