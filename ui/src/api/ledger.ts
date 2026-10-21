import { config } from '../config';
import { apiFetch } from './client';

export interface Account {
  id: string;
  ownerName: string;
  currency: string;
  balance: number | string;
}

export interface Entry {
  id: number;
  transactionId: string;
  accountId: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: number | string;
  createdAt: string;
}

export interface TransferResult {
  transactionId: string;
  /** true when this idempotency key was already processed: nothing was applied a second time. */
  replayed: boolean;
}

export interface TransferBody {
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  description?: string;
}

const ledger = (path: string) => `/api/v1${path}`;

export const listAccounts = (token: string | undefined) =>
  apiFetch<Account[]>(config.ledgerApi, ledger('/accounts'), { token });

export const createAccount = (token: string | undefined, ownerName: string, currency: string) =>
  apiFetch<Account>(config.ledgerApi, ledger('/accounts'), { method: 'POST', token, body: { ownerName, currency } });

export const getAccount = (token: string | undefined, id: string) =>
  apiFetch<Account>(config.ledgerApi, ledger(`/accounts/${id}`), { token });

export const deposit = (token: string | undefined, id: string, amount: number, idempotencyKey: string) =>
  apiFetch<TransferResult>(config.ledgerApi, ledger(`/accounts/${id}/deposits`), {
    method: 'POST',
    token,
    body: { amount },
    idempotencyKey,
  });

export const transfer = (token: string | undefined, body: TransferBody, idempotencyKey: string) =>
  apiFetch<TransferResult>(config.ledgerApi, ledger('/transfers'), { method: 'POST', token, body, idempotencyKey });

export const getStatement = (token: string | undefined, id: string, limit = 50) =>
  apiFetch<Entry[]>(config.ledgerApi, ledger(`/accounts/${id}/statement?limit=${limit}`), { token });
