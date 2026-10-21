import { useRef } from 'react';

// Why this exists: the API applies a money-moving request once per idempotency key, however many times it is sent.
// A retry after a timeout must therefore reuse the SAME key (so it cannot pay twice), while a different request must use
// a NEW key (the API refuses to reuse one key for different content).
//
//   keyFor(payload)  same payload as last time -> same key; changed payload -> new key
//   done()           the request succeeded, so the next request is a new one: forget the key
export function useIdempotencyKey() {
  const last = useRef<{ payload: string; key: string } | undefined>(undefined);

  return {
    keyFor(payload: unknown): string {
      const serialised = JSON.stringify(payload);
      if (last.current?.payload !== serialised) {
        last.current = { payload: serialised, key: crypto.randomUUID() };
      }
      return last.current.key;
    },
    done(): void {
      last.current = undefined;
    },
  };
}
