// Display helpers. The API sends amounts as JSON numbers (or strings); these turn them into text for people.

export function toNumber(value: number | string | null | undefined): number {
  return value === null || value === undefined ? NaN : Number(value);
}

export function formatMoney(value: number | string | null | undefined, currency: string): string {
  const n = toNumber(value);
  if (Number.isNaN(n)) return '–';
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(n);
  } catch {
    return `${n.toFixed(2)} ${currency}`; // a code Intl does not know
  }
}

export function formatQuantity(value: number | string): string {
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 6 }).format(Number(value));
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

export const shortId = (id: string) => id.slice(0, 8);

export const isUuid = (text: string) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(text.trim());

function parsePositive(text: string, maxDecimals: number): number | undefined {
  const t = text.trim();
  if (!new RegExp(`^\\d+(\\.\\d{1,${maxDecimals}})?$`).test(t)) return undefined;
  const n = Number(t);
  return n > 0 ? n : undefined;
}

/** Money: a positive number with at most 4 decimals (the ledger stores 4). Undefined when not valid. */
export const parseAmount = (text: string) => parsePositive(text, 4);

/** Share quantity: a positive number with at most 6 decimals (the trading API allows 6). */
export const parseQuantity = (text: string) => parsePositive(text, 6);

/** A plain amount, for places where the currency is not known (a notification carries only the number). */
export function formatAmount(value: number | string): string {
  const n = toNumber(value);
  if (Number.isNaN(n)) return '–';
  return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(n);
}
