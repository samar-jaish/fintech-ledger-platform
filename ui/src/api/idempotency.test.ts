import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useIdempotencyKey } from './idempotency';

describe('useIdempotencyKey', () => {
  it('reuses the key for the same request, so a retry cannot be applied twice', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const first = result.current.keyFor({ to: 'a', amount: 5 });
    expect(result.current.keyFor({ to: 'a', amount: 5 })).toBe(first);
  });

  it('uses a new key when the request changes, because the API refuses one key for two different requests', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const first = result.current.keyFor({ to: 'a', amount: 5 });
    expect(result.current.keyFor({ to: 'a', amount: 6 })).not.toBe(first);
  });

  it('forgets the key once the request succeeded', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const first = result.current.keyFor({ amount: 5 });
    result.current.done();
    expect(result.current.keyFor({ amount: 5 })).not.toBe(first);
  });

  it('produces keys the API accepts (at most 64 characters)', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    expect(result.current.keyFor({}).length).toBeLessThanOrEqual(64);
  });
});
