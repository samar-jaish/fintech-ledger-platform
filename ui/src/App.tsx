import { NavLink, Route, Routes } from 'react-router';
import { useAuth } from './auth/AuthContext';
import { AccountPage, MoneyTab, TradingTab, UnknownRoute } from './pages/AccountPage';
import { AccountsPage } from './pages/AccountsPage';
import { NotificationBell } from './notifications/NotificationBell';

export default function App() {
  const { state, login, logout } = useAuth();

  return (
    <>
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden="true" />
          Ledger Platform
          {state.status === 'authenticated' && (
            <nav className="mainnav" aria-label="Main">
              <NavLink to="/accounts">Accounts</NavLink>
            </nav>
          )}
        </div>
        {state.status === 'authenticated' && (
          <div className="who">
            <NotificationBell />
            <span className="who-name">{state.displayName}</span>
            {state.roles.map((role) => (
              <span key={role} className="badge">
                {role}
              </span>
            ))}
            <button className="btn btn-quiet" onClick={logout}>
              Sign out
            </button>
          </div>
        )}
      </header>

      <main className="page">
        {state.status === 'loading' && <p className="muted">Checking your session…</p>}

        {state.status === 'error' && (
          <section className="card" role="alert">
            <h1>Sign-in did not complete</h1>
            <p className="muted">{state.message}</p>
            <button className="btn" onClick={login}>
              Try again
            </button>
          </section>
        )}

        {state.status === 'anonymous' && (
          <section className="card hero">
            <h1>Your money, double-entry accurate.</h1>
            <p className="muted">
              Sign in to open accounts, move money and trade. Every action is checked by the ledger service, never by
              this page.
            </p>
            <button className="btn" onClick={login}>
              Sign in
            </button>
            <p className="hint">
              Local demo users: <code>alice</code> / <code>alice123</code> or <code>bob</code> / <code>bob123</code>
            </p>
          </section>
        )}

        {state.status === 'authenticated' && (
          <Routes>
            <Route path="/accounts" element={<AccountsPage />} />
            <Route path="/accounts/:id" element={<AccountPage />}>
              <Route index element={<MoneyTab />} />
              <Route path="trading" element={<TradingTab />} />
            </Route>
            <Route path="*" element={<UnknownRoute />} />
          </Routes>
        )}
      </main>
    </>
  );
}
