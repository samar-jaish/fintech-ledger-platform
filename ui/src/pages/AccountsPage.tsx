import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import { listAccounts } from '../api/ledger';
import { useAuth } from '../auth/AuthContext';
import { ErrorNote } from '../components/ErrorNote';
import { OpenAccountForm } from '../components/OpenAccountForm';
import { formatMoney, shortId } from '../format';

export function AccountsPage() {
  const { getToken } = useAuth();
  const navigate = useNavigate();
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: () => listAccounts(getToken()) });

  return (
    <>
      <section className="card">
        <h1>Your accounts</h1>
        {accounts.isPending && <p className="muted">Loading your accounts…</p>}
        <ErrorNote error={accounts.error} />
        {accounts.data?.length === 0 && <p className="muted">You have no accounts yet. Open your first one below.</p>}
        {accounts.data && accounts.data.length > 0 && (
          <ul className="account-list">
            {accounts.data.map((a) => (
              <li key={a.id}>
                <Link to={`/accounts/${a.id}`} className="account-link">
                  <span className="account-name">
                    {a.ownerName}
                    <span className="muted"> · {shortId(a.id)}</span>
                  </span>
                  <span className="account-balance">{formatMoney(a.balance, a.currency)}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <h2>Open an account</h2>
        <OpenAccountForm onOpened={(account) => navigate(`/accounts/${account.id}`)} />
      </section>
    </>
  );
}
