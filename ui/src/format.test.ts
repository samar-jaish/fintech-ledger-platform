import { describe, expect, it } from 'vitest';
import { formatMoney, formatQuantity, isUuid, parseAmount, parseQuantity, shortId } from './format';

describe('formatMoney', () => {
  it('formats numbers and numeric strings', () => {
    expect(formatMoney(1234.5, 'USD')).toBe('$1,234.50');
    expect(formatMoney('559.0965', 'USD')).toBe('$559.10');
  });
  it('shows a dash when there is no value', () => {
    expect(formatMoney(null, 'USD')).toBe('–');
    expect(formatMoney(undefined, 'USD')).toBe('–');
  });
  it('falls back to the plain code for a currency the browser does not know', () => {
    expect(formatMoney(5, 'ZZZ1')).toBe('5.00 ZZZ1');
  });
});

describe('parseAmount', () => {
  it('accepts positive money with up to 4 decimals', () => {
    expect(parseAmount('100')).toBe(100);
    expect(parseAmount(' 12.3456 ')).toBe(12.3456);
  });
  it.each(['', '0', '0.00', '-5', '1.23456', 'abc', '1,000', '1e3'])('rejects %j', (text) => {
    expect(parseAmount(text)).toBeUndefined();
  });
});

describe('parseQuantity', () => {
  it('accepts up to 6 decimals', () => {
    expect(parseQuantity('0.000001')).toBe(0.000001);
    expect(parseQuantity('2.5')).toBe(2.5);
  });
  it('rejects 7 decimals and non-positive values', () => {
    expect(parseQuantity('0.0000001')).toBeUndefined();
    expect(parseQuantity('0')).toBeUndefined();
  });
});

describe('small helpers', () => {
  it('recognises a UUID', () => {
    expect(isUuid('3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90')).toBe(true);
    expect(isUuid('not-an-id')).toBe(false);
  });
  it('shortens ids and quantities for display', () => {
    expect(shortId('3f2a9c1e-5b7d')).toBe('3f2a9c1e');
    expect(formatQuantity(5)).toBe('5');
    expect(formatQuantity('0.5')).toBe('0.5');
  });
});
