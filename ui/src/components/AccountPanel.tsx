import { useState, type FormEvent } from 'react';
import { createAccount, getAccount, type Account } from '../api/ledger';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

export function AccountPanel() {
  const { state, getToken } = useAuth();
  const [ownerName, setOwnerName] = useState(state.status === 'authenticated' ? state.displayName : '');
  const [currency, setCurrency] = useState('USD');
  const [account, setAccount] = useState<Account>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<Account>) {
    setBusy(true);
    setError(undefined);
    try {
      setAccount(await action());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  function onOpen(event: FormEvent) {
    event.preventDefault();
    void run(() => createAccount(getToken(), ownerName.trim(), currency.trim().toUpperCase()));
  }

  return (
    <section className="card">
      <h1>Open an account</h1>
      <p className="muted">This call goes from your browser to the ledger service with your access token.</p>

      <form onSubmit={onOpen} className="form">
        <label>
          Account holder
          <input value={ownerName} onChange={(e) => setOwnerName(e.target.value)} maxLength={100} required />
        </label>
        <label>
          Currency
          <input value={currency} onChange={(e) => setCurrency(e.target.value)} maxLength={3} pattern="[A-Za-z]{3}" required />
        </label>
        <button className="btn" disabled={busy}>
          {busy ? 'Opening…' : 'Open account'}
        </button>
      </form>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      {account && (
        <dl className="result" aria-label="Account details">
          <dt>Account id</dt>
          <dd>
            <code>{account.id}</code>
          </dd>
          <dt>Holder</dt>
          <dd>{account.ownerName}</dd>
          <dt>Balance</dt>
          <dd>
            {Number(account.balance).toFixed(2)} {account.currency}
          </dd>
          <dd>
            <button className="btn btn-quiet" disabled={busy} onClick={() => void run(() => getAccount(getToken(), account.id))}>
              Refresh balance
            </button>
          </dd>
        </dl>
      )}
    </section>
  );
}
