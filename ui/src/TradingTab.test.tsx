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
const trading = vi.hoisted(() => ({ getPortfolio: vi.fn(), getTrades: vi.fn(), placeTrade: vi.fn() }));
vi.mock('./api/ledger', () => ledger);
vi.mock('./api/trading', () => trading);

const ID = '3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90';

const portfolio = (overrides = {}) => ({
  accountId: ID,
  currency: 'USD',
  cash: 559.0965,
  positions: [
    { symbol: 'AAPL', quantity: 5, avgCost: 88.1807, costBasis: 440.9035, price: 90, marketValue: 450, unrealizedPnl: 9.0965, priceAvailable: true },
  ],
  totalMarketValue: 450,
  totalUnrealizedPnl: 9.0965,
  totalValue: 1009.0965,
  complete: true,
  pendingTrades: 0,
  ...overrides,
});

const completedTrade = {
  id: 't1', accountId: ID, symbol: 'AAPL', side: 'BUY', quantity: 5, price: 88.1807, notional: 440.9035,
  currency: 'USD', createdAt: '2026-10-20T10:00:00Z', status: 'COMPLETED', rejectReason: null, replayed: false,
};

beforeEach(() => {
  vi.clearAllMocks();
  auth.state = { status: 'authenticated', username: 'alice', displayName: 'Alice Demo', roles: ['USER'], accessToken: 'token-1' };
  ledger.getAccount.mockResolvedValue({ id: ID, ownerName: 'Alice Demo', currency: 'USD', balance: 559.0965 });
  trading.getPortfolio.mockResolvedValue(portfolio());
  trading.getTrades.mockResolvedValue([]);
});

const open = () => renderAt(`/accounts/${ID}/trading`, <App />);
const quantityBox = () => screen.findByLabelText('Quantity');

describe('portfolio', () => {
  it('shows cash, totals and each holding', async () => {
    open();
    const holdings = await screen.findByRole('table', { name: 'Holdings' });
    expect(within(holdings).getByText('AAPL')).toBeInTheDocument();
    expect(within(holdings).getByText('$450.00')).toBeInTheDocument();
    // The cash figure appears in the account header and in the Cash box, so look at the Cash box itself.
    expect(screen.getByText('Cash').nextElementSibling).toHaveTextContent('$559.10');
    expect(screen.getByText('Total value').nextElementSibling).toHaveTextContent('$1,009.10');
    expect(trading.getPortfolio).toHaveBeenCalledWith('token-1', ID);
  });

  it('invites a new account to buy its first stock', async () => {
    trading.getPortfolio.mockResolvedValue(portfolio({ positions: [], totalMarketValue: 0, totalUnrealizedPnl: 0 }));
    open();
    expect(await screen.findByText(/no holdings yet/i)).toBeInTheDocument();
  });

  it('warns while trades wait for the ledger, and when prices are missing', async () => {
    trading.getPortfolio.mockResolvedValue(portfolio({ pendingTrades: 2, complete: false }));
    open();
    expect(await screen.findByText(/2 trades are waiting for the ledger/i)).toBeInTheDocument();
    expect(screen.getByText(/could not be priced/i)).toBeInTheDocument();
  });
});

describe('trading', () => {
  const buy = () => userEvent.click(screen.getByRole('button', { name: /buy at market price/i }));

  it('places a buy with a key and reports the price', async () => {
    trading.placeTrade.mockResolvedValue(completedTrade);
    open();
    await userEvent.type(await quantityBox(), '5');
    await buy();

    expect(trading.placeTrade).toHaveBeenCalledWith(
      'token-1',
      { accountId: ID, symbol: 'AAPL', side: 'BUY', quantity: 5 },
      expect.any(String),
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Bought 5 AAPL at $88.18');
    expect(trading.getPortfolio.mock.calls.length).toBeGreaterThan(1);
  });

  it('places a sell', async () => {
    trading.placeTrade.mockResolvedValue({ ...completedTrade, side: 'SELL' });
    open();
    await userEvent.selectOptions(await screen.findByLabelText('Side'), 'SELL');
    await userEvent.type(await quantityBox(), '2');
    await userEvent.click(screen.getByRole('button', { name: /sell at market price/i }));

    expect(trading.placeTrade).toHaveBeenCalledWith(
      'token-1',
      { accountId: ID, symbol: 'AAPL', side: 'SELL', quantity: 2 },
      expect.any(String),
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Sold 5 AAPL');
  });

  it('says a pending trade was accepted but is not confirmed yet', async () => {
    trading.placeTrade.mockResolvedValue({ ...completedTrade, status: 'PENDING' });
    open();
    await userEvent.type(await quantityBox(), '5');
    await buy();
    expect(await screen.findByRole('status')).toHaveTextContent('has not confirmed the cash yet');
  });

  it('shows why a trade was rejected', async () => {
    trading.placeTrade.mockRejectedValue(new ApiError(422, 'INSUFFICIENT_FUNDS', 'Not enough cash for this trade'));
    open();
    await userEvent.type(await quantityBox(), '500000');
    await buy();
    expect(await screen.findByRole('alert')).toHaveTextContent('Not enough cash');
  });

  it('refuses a bad quantity or symbol without calling the API', async () => {
    open();
    await userEvent.type(await quantityBox(), '0');
    await buy();
    expect(await screen.findByRole('alert')).toHaveTextContent('positive quantity');

    await userEvent.clear(screen.getByLabelText('Symbol'));
    await userEvent.type(screen.getByLabelText('Symbol'), '123');
    await userEvent.clear(screen.getByLabelText('Quantity'));
    await userEvent.type(screen.getByLabelText('Quantity'), '1');
    await buy();
    expect(await screen.findByRole('alert')).toHaveTextContent('stock symbol');
    expect(trading.placeTrade).not.toHaveBeenCalled();
  });

  it('retries the same trade with the same key', async () => {
    trading.placeTrade.mockRejectedValue(new ApiError(503, 'LEDGER_UNAVAILABLE', 'The ledger service is temporarily unavailable. Retry later.'));
    open();
    await userEvent.type(await quantityBox(), '5');
    await buy();
    await screen.findByRole('alert');
    await buy();
    await vi.waitFor(() => expect(trading.placeTrade).toHaveBeenCalledTimes(2));
    expect(trading.placeTrade.mock.calls[1]![2]).toBe(trading.placeTrade.mock.calls[0]![2]);
  });
});

describe('trade history', () => {
  it('lists trades with their status, including rejected ones with the reason', async () => {
    trading.getTrades.mockResolvedValue([
      { ...completedTrade, id: 't2', status: 'REJECTED', rejectReason: 'INSUFFICIENT_FUNDS', quantity: 900 },
      completedTrade,
    ]);
    open();

    const table = await screen.findByRole('table', { name: /trade history/i });
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Rejected');
    expect(rows[1]).toHaveTextContent('insufficient funds');
    expect(rows[2]).toHaveTextContent('Buy 5 AAPL');
    expect(rows[2]).toHaveTextContent('Completed');
  });
});
