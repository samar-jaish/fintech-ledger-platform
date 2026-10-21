import { useQuery } from '@tanstack/react-query';
import { Link, NavLink, Navigate, Outlet, useParams } from 'react-router';
import { getAccount } from '../api/ledger';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { DepositForm } from '../components/DepositForm';
import { ErrorNote } from '../components/ErrorNote';
import { PortfolioView } from '../components/PortfolioView';
import { Statement } from '../components/Statement';
import { TradeForm } from '../components/TradeForm';
import { TradeHistory } from '../components/TradeHistory';
import { TransferForm } from '../components/TransferForm';
import { formatMoney } from '../format';

/** The account's header (holder, balance) and its two tabs. The tabs are child routes rendered in the Outlet. */
export function AccountPage() {
  const { id = '' } = useParams();
  const { getToken } = useAuth();
  const account = useQuery({
    queryKey: ['account', id],
    queryFn: () => getAccount(getToken(), id),
    retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
  });

  if (account.isPending) return <p className="muted">Loading account…</p>;
  if (account.isError) {
    const notFound = account.error instanceof ApiError && account.error.status === 404;
    return (
      <section className="card">
        <h1>{notFound ? 'Account not found' : 'Could not load the account'}</h1>
        {notFound ? <p className="muted">It does not exist, or it is not yours.</p> : <ErrorNote error={account.error} />}
        <Link to="/accounts">Back to your accounts</Link>
      </section>
    );
  }

  const a = account.data;
  return (
    <>
      <p className="crumb">
        <Link to="/accounts">← All accounts</Link>
      </p>
      <section className="card">
        <p className="muted">{a.ownerName}</p>
        <p className="balance" aria-label="Balance">
          {formatMoney(a.balance, a.currency)}
        </p>
        <p className="hint">
          Account id <code>{a.id}</code>
        </p>
        <nav className="tabs" aria-label="Account sections">
          <NavLink to="" end>
            Money
          </NavLink>
          <NavLink to="trading">Trading</NavLink>
        </nav>
      </section>
      <Outlet context={{ account: a }} />
    </>
  );
}

export function MoneyTab() {
  const { id = '' } = useParams();
  const { getToken } = useAuth();
  const account = useQuery({ queryKey: ['account', id], queryFn: () => getAccount(getToken(), id) });
  const currency = account.data?.currency ?? 'USD';

  return (
    <>
      <section className="card">
        <h2>Add money</h2>
        <DepositForm accountId={id} />
      </section>
      <section className="card">
        <h2>Send money</h2>
        <TransferForm accountId={id} />
      </section>
      <section className="card">
        <h2>Statement</h2>
        <Statement accountId={id} currency={currency} />
      </section>
    </>
  );
}

export function TradingTab() {
  const { id = '' } = useParams();
  return (
    <>
      <section className="card">
        <h2>Portfolio</h2>
        <PortfolioView accountId={id} />
      </section>
      <section className="card">
        <h2>Trade</h2>
        <TradeForm accountId={id} />
      </section>
      <section className="card">
        <h2>Trade history</h2>
        <TradeHistory accountId={id} />
      </section>
    </>
  );
}

export const UnknownRoute = () => <Navigate to="/accounts" replace />;
