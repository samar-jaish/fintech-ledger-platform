import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { ApiError } from './api/client';
import type { AuthState } from './auth/AuthContext';

// The screens are tested against a fake session and a fake API: no browser login, no network.
const login = vi.fn();
const logout = vi.fn();
let state: AuthState;

vi.mock('./auth/AuthContext', () => ({
  useAuth: () => ({ state, login, logout, getToken: () => 'token-1' }),
}));

const createAccount = vi.fn();
const getAccount = vi.fn();
vi.mock('./api/ledger', () => ({
  createAccount: (...args: unknown[]) => createAccount(...args),
  getAccount: (...args: unknown[]) => getAccount(...args),
}));

const alice: AuthState = {
  status: 'authenticated',
  username: 'alice',
  displayName: 'Alice Demo',
  roles: ['USER'],
  accessToken: 'token-1',
};

beforeEach(() => {
  vi.clearAllMocks();
  state = { status: 'anonymous' };
});

describe('signed out', () => {
  it('offers sign-in and starts the login when asked', async () => {
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(login).toHaveBeenCalledOnce();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
  });

  it('shows a loading message while the session is checked', () => {
    state = { status: 'loading' };
    render(<App />);
    expect(screen.getByText(/checking your session/i)).toBeInTheDocument();
  });

  it('shows why sign-in failed and lets the user retry', async () => {
    state = { status: 'error', message: 'State mismatch' };
    render(<App />);
    expect(screen.getByRole('alert')).toHaveTextContent('State mismatch');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(login).toHaveBeenCalledOnce();
  });
});

describe('signed in', () => {
  beforeEach(() => {
    state = alice;
  });

  it('shows who is signed in with their roles, and signs out', async () => {
    render(<App />);
    expect(screen.getByText('Alice Demo')).toBeInTheDocument();
    expect(screen.getByText('USER')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(logout).toHaveBeenCalledOnce();
  });

  it('opens an account with the token and shows the result', async () => {
    createAccount.mockResolvedValue({ id: 'acc-1', ownerName: 'Alice Demo', currency: 'USD', balance: 0 });
    render(<App />);

    await userEvent.click(screen.getByRole('button', { name: 'Open account' }));

    expect(createAccount).toHaveBeenCalledWith('token-1', 'Alice Demo', 'USD');
    const details = await screen.findByLabelText('Account details');
    expect(details).toHaveTextContent('acc-1');
    expect(details).toHaveTextContent('0.00 USD');
  });

  it('shows the server\'s reason when the call fails', async () => {
    createAccount.mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'You do not have permission to do this.'));
    render(<App />);

    await userEvent.click(screen.getByRole('button', { name: 'Open account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission');
  });

  it('refreshes the balance of the opened account', async () => {
    createAccount.mockResolvedValue({ id: 'acc-1', ownerName: 'Alice Demo', currency: 'USD', balance: 0 });
    getAccount.mockResolvedValue({ id: 'acc-1', ownerName: 'Alice Demo', currency: 'USD', balance: '125.5' });
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Open account' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Refresh balance' }));

    expect(getAccount).toHaveBeenCalledWith('token-1', 'acc-1');
    expect(await screen.findByLabelText('Account details')).toHaveTextContent('125.50 USD');
  });
});
