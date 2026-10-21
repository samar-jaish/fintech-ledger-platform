import { config } from '../config';
import { apiFetch } from './client';

export interface Account {
  id: string;
  ownerName: string;
  currency: string;
  balance: string | number;
}

export function createAccount(token: string | undefined, ownerName: string, currency: string): Promise<Account> {
  return apiFetch<Account>(config.ledgerApi, '/api/v1/accounts', { method: 'POST', token, body: { ownerName, currency } });
}

export function getAccount(token: string | undefined, id: string): Promise<Account> {
  return apiFetch<Account>(config.ledgerApi, `/api/v1/accounts/${id}`, { token });
}
