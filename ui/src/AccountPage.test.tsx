import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { ApiError } from './api/client';
import type { AuthState } from './auth/AuthContext';
import { renderAt } from './test/render';

const auth = vi.hoisted(() => ({ state: {} as AuthState }));
vi.mock('./auth/AuthContext', () => ({
  useAuth: () => ({ state: auth.state, login: vi.fn(), logout: vi.fn(), getToken: () => 'token-1' }),
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

const ID = '3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90';
const OTHER = 'b0000000-0000-4000-8000-000000000001';

beforeEach(() => {
  vi.clearAllMocks();
  auth.state = { status: 'authenticated', username: 'alice', displayName: 'Alice Demo', roles: ['USER'], accessToken: 'token-1' };
  ledger.getAccount.mockResolvedValue({ id: ID, ownerName: 'Alice Demo', currency: 'USD', balance: 100 });
  ledger.getStatement.mockResolvedValue([]);
});

const open = () => renderAt(`/accounts/${ID}`, <App />);

describe('account page', () => {
  it('shows the balance and the account id', async () => {
    open();
    expect(await screen.findByLabelText('Balance')).toHaveTextContent('$100.00');
    expect(screen.getByText(ID)).toBeInTheDocument();
  });

  it('says "not found" for an account that is not the user\'s, without revealing anything else', async () => {
    ledger.getAccount.mockRejectedValue(new ApiError(404, 'ACCOUNT_NOT_FOUND', 'Account not found'));
    open();
    expect(await screen.findByRole('heading', { name: 'Account not found' })).toBeInTheDocument();
    expect(screen.getByText(/not yours/i)).toBeInTheDocument();
  });

  it('shows the statement with money in and out', async () => {
    ledger.getStatement.mockResolvedValue([
      { id: 2, transactionId: 'aaaaaaaa-1111-4111-8111-111111111111', accountId: ID, direction: 'DEBIT', amount: 25, createdAt: '2026-10-20T10:00:00Z' },
      { id: 1, transactionId: 'bbbbbbbb-2222-4222-8222-222222222222', accountId: ID, direction: 'CREDIT', amount: 100, createdAt: '2026-10-20T09:00:00Z' },
    ]);
    open();

    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Money out');
    expect(rows[1]).toHaveTextContent('−$25.00');
    expect(rows[2]).toHaveTextContent('Money in');
    expect(rows[2]).toHaveTextContent('+$100.00');
  });

  it('explains an empty statement', async () => {
    open();
    expect(await screen.findByText(/no activity yet/i)).toBeInTheDocument();
  });
});

describe('add money', () => {
  it('sends the amount with an idempotency key and refreshes', async () => {
    ledger.deposit.mockResolvedValue({ transactionId: 't1', replayed: false });
    open();

    await userEvent.type(await screen.findByLabelText('Amount to add'), '250.5');
    await userEvent.click(screen.getByRole('button', { name: 'Add money' }));

    expect(ledger.deposit).toHaveBeenCalledWith('token-1', ID, 250.5, expect.any(String));
    // The balance and statement are asked for again after the change.
    expect(ledger.getAccount.mock.calls.length).toBeGreaterThan(1);
    expect(ledger.getStatement.mock.calls.length).toBeGreaterThan(1);
  });

  it('refuses a bad amount without calling the API', async () => {
    open();
    await userEvent.type(await screen.findByLabelText('Amount to add'), '-5');
    await userEvent.click(screen.getByRole('button', { name: 'Add money' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('positive amount');
    expect(ledger.deposit).not.toHaveBeenCalled();
  });
});

describe('send money', () => {
  const fill = async (to: string, amount: string) => {
    const toBox = await screen.findByLabelText('To account id');
    await userEvent.clear(toBox);
    await userEvent.type(toBox, to);
    const amountBox = screen.getByLabelText('Amount');
    await userEvent.clear(amountBox);
    await userEvent.type(amountBox, amount);
  };
  const send = () => userEvent.click(screen.getByRole('button', { name: 'Send money' }));

  it('checks the receiving account id before calling the API', async () => {
    open();
    await fill('not-an-id', '10');
    await send();
    expect(await screen.findByRole('alert')).toHaveTextContent('receiving account id');
    expect(ledger.transfer).not.toHaveBeenCalled();
  });

  it('refuses to send to the same account', async () => {
    open();
    await fill(ID, '10');
    await send();
    expect(await screen.findByRole('alert')).toHaveTextContent('different account');
    expect(ledger.transfer).not.toHaveBeenCalled();
  });

  it('sends the transfer and confirms', async () => {
    ledger.transfer.mockResolvedValue({ transactionId: 't1', replayed: false });
    open();
    await fill(OTHER.toUpperCase(), '10.25');
    await send();

    expect(ledger.transfer).toHaveBeenCalledWith(
      'token-1',
      { fromAccountId: ID, toAccountId: OTHER, amount: 10.25 },
      expect.any(String),
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Transfer sent.');
  });

  it('tells the user when the same transfer had already been applied', async () => {
    ledger.transfer.mockResolvedValue({ transactionId: 't1', replayed: true });
    open();
    await fill(OTHER, '10');
    await send();
    expect(await screen.findByRole('status')).toHaveTextContent('already applied');
  });

  it('shows the server\'s reason, such as insufficient funds', async () => {
    ledger.transfer.mockRejectedValue(new ApiError(409, 'INSUFFICIENT_FUNDS', 'Insufficient funds in account'));
    open();
    await fill(OTHER, '10');
    await send();
    expect(await screen.findByRole('alert')).toHaveTextContent('Insufficient funds');
  });

  it('reuses the key when the same transfer is retried, and uses a new one when it changes', async () => {
    ledger.transfer.mockRejectedValue(new ApiError(0, 'NETWORK', 'Could not reach the server. Is the platform running?'));
    open();
    await fill(OTHER, '10');

    await send();
    await screen.findByRole('alert');
    await send(); // a retry of the very same transfer
    await vi.waitFor(() => expect(ledger.transfer).toHaveBeenCalledTimes(2));
    const [firstKey, retryKey] = ledger.transfer.mock.calls.map((call) => call[2] as string);
    expect(retryKey).toBe(firstKey);

    await fill(OTHER, '11'); // a different transfer
    await send();
    await vi.waitFor(() => expect(ledger.transfer).toHaveBeenCalledTimes(3));
    expect(ledger.transfer.mock.calls[2]![2]).not.toBe(firstKey);
  });
});
