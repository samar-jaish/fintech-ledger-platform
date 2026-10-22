import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { ApiError } from './api/client';
import type { AuthState } from './auth/AuthContext';
import { renderAt } from './test/render';

// The screens are tested against a fake session and a fake API: no browser login, no network.
const auth = vi.hoisted(() => ({ state: { status: 'anonymous' } as AuthState, login: vi.fn(), logout: vi.fn() }));
vi.mock('./auth/AuthContext', () => ({
  useAuth: () => ({ state: auth.state, login: auth.login, logout: auth.logout, getToken: () => 'token-1' }),
}));

const ledger = vi.hoisted(() => ({
  listAccounts: vi.fn(),
  createAccount: vi.fn(),
  getAccount: vi.fn(),
  deposit: vi.fn(),
  transfer: vi.fn(),
  getStatement: vi.fn(),
}));
vi.mock('./api/ledger', () => ledger);
vi.mock('./api/trading', () => ({ getPortfolio: vi.fn(), getTrades: vi.fn(), placeTrade: vi.fn() }));
// The bell in the header talks to the notification service; here it just has nothing to show.
vi.mock('./api/notifications', () => ({
  listNotifications: vi.fn().mockResolvedValue([]),
  openNotificationStream: vi.fn(() => new Promise<void>(() => {})),
}));

const ID = '3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90';
const alice: AuthState = {
  status: 'authenticated',
  username: 'alice',
  displayName: 'Alice Demo',
  roles: ['USER'],
  accessToken: 'token-1',
};

beforeEach(() => {
  vi.clearAllMocks();
  auth.state = { status: 'anonymous' };
});

describe('signed out', () => {
  it('offers sign-in and starts the login when asked', async () => {
    renderAt('/', <App />);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(auth.login).toHaveBeenCalledOnce();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
  });

  it('shows no account screens, even at an account address', () => {
    renderAt(`/accounts/${ID}`, <App />);
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(ledger.getAccount).not.toHaveBeenCalled();
  });

  it('shows a loading message while the session is checked', () => {
    auth.state = { status: 'loading' };
    renderAt('/', <App />);
    expect(screen.getByText(/checking your session/i)).toBeInTheDocument();
  });

  it('shows why sign-in failed and lets the user retry', async () => {
    auth.state = { status: 'error', message: 'State mismatch' };
    renderAt('/', <App />);
    expect(screen.getByRole('alert')).toHaveTextContent('State mismatch');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(auth.login).toHaveBeenCalledOnce();
  });
});

describe('signed in: accounts', () => {
  beforeEach(() => {
    auth.state = alice;
  });

  it('shows who is signed in with their roles, and signs out', async () => {
    ledger.listAccounts.mockResolvedValue([]);
    renderAt('/accounts', <App />);
    expect(screen.getByText('Alice Demo')).toBeInTheDocument();
    expect(screen.getByText('USER')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(auth.logout).toHaveBeenCalledOnce();
  });

  it('sends the front page to the accounts list', async () => {
    ledger.listAccounts.mockResolvedValue([]);
    renderAt('/', <App />);
    expect(await screen.findByRole('heading', { name: 'Your accounts' })).toBeInTheDocument();
  });

  it('lists the user\'s accounts with balances, each linking to its page', async () => {
    ledger.listAccounts.mockResolvedValue([
      { id: ID, ownerName: 'Alice Demo', currency: 'USD', balance: 1000 },
      { id: 'b0000000-0000-4000-8000-000000000000', ownerName: 'Savings', currency: 'EUR', balance: '12.5' },
    ]);
    renderAt('/accounts', <App />);

    const first = await screen.findByRole('link', { name: /Alice Demo/ });
    expect(first).toHaveAttribute('href', `/accounts/${ID}`);
    expect(first).toHaveTextContent('$1,000.00');
    expect(screen.getByRole('link', { name: /Savings/ })).toHaveTextContent('€12.50');
    expect(ledger.listAccounts).toHaveBeenCalledWith('token-1');
  });

  it('invites a new user to open a first account', async () => {
    ledger.listAccounts.mockResolvedValue([]);
    renderAt('/accounts', <App />);
    expect(await screen.findByText(/no accounts yet/i)).toBeInTheDocument();
  });

  it('says so when the accounts cannot be loaded', async () => {
    ledger.listAccounts.mockRejectedValue(new ApiError(0, 'NETWORK', 'Could not reach the server. Is the platform running?'));
    renderAt('/accounts', <App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server');
  });

  it('opens an account and goes to its page', async () => {
    const created = { id: ID, ownerName: 'Alice Demo', currency: 'USD', balance: 0 };
    ledger.listAccounts.mockResolvedValue([]);
    ledger.createAccount.mockResolvedValue(created);
    ledger.getAccount.mockResolvedValue(created);
    ledger.getStatement.mockResolvedValue([]);
    renderAt('/accounts', <App />);

    await userEvent.click(await screen.findByRole('button', { name: 'Open account' }));

    expect(ledger.createAccount).toHaveBeenCalledWith('token-1', 'Alice Demo', 'USD');
    expect(await screen.findByLabelText('Balance')).toHaveTextContent('$0.00');
  });

  it('shows the server\'s reason when opening fails', async () => {
    ledger.listAccounts.mockResolvedValue([]);
    ledger.createAccount.mockRejectedValue(new ApiError(400, 'INVALID_REQUEST', 'currency must be a 3-letter upper-case code, e.g. USD'));
    renderAt('/accounts', <App />);

    await userEvent.click(await screen.findByRole('button', { name: 'Open account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('currency must be a 3-letter');
  });
});
