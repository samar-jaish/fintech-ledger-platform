import { config } from '../config';
import { apiFetch } from './client';

export type Side = 'BUY' | 'SELL';
export type TradeStatus = 'PENDING' | 'COMPLETED' | 'REJECTED';

export interface Position {
  symbol: string;
  quantity: number;
  avgCost: number;
  costBasis: number;
  price: number | null;
  marketValue: number | null;
  unrealizedPnl: number | null;
  priceAvailable: boolean;
}

export interface Portfolio {
  accountId: string;
  currency: string;
  cash: number;
  positions: Position[];
  totalMarketValue: number;
  totalUnrealizedPnl: number;
  totalValue: number;
  /** false when some position could not be priced, so the totals are partial. */
  complete: boolean;
  /** Trades accepted but not yet confirmed by the ledger; cash and positions can briefly disagree. */
  pendingTrades: number;
}

export interface Trade {
  id: string;
  accountId: string;
  symbol: string;
  side: Side;
  quantity: number;
  price: number;
  notional: number;
  currency: string;
  createdAt: string;
  status: TradeStatus;
  rejectReason: string | null;
  replayed: boolean;
}

export interface TradeBody {
  accountId: string;
  symbol: string;
  side: Side;
  quantity: number;
}

const trading = (path: string) => `/api/v1${path}`;

export const getPortfolio = (token: string | undefined, accountId: string) =>
  apiFetch<Portfolio>(config.tradingApi, trading(`/accounts/${accountId}/portfolio`), { token });

export const getTrades = (token: string | undefined, accountId: string, limit = 50) =>
  apiFetch<Trade[]>(config.tradingApi, trading(`/accounts/${accountId}/trades?limit=${limit}`), { token });

/** Resolves for 201 (done), 200 (replay) and 202 (accepted, ledger not confirmed yet: status PENDING). */
export const placeTrade = (token: string | undefined, body: TradeBody, idempotencyKey: string) =>
  apiFetch<Trade>(config.tradingApi, trading('/trades'), { method: 'POST', token, body, idempotencyKey });
