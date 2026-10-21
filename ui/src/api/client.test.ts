import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from './client';

function reply(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));
}

afterEach(() => vi.restoreAllMocks());

describe('apiFetch', () => {
  it('sends the bearer token, the idempotency key and a JSON body', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockReturnValue(reply(201, { ok: true }));

    await apiFetch('http://api', '/x', { method: 'POST', body: { a: 1 }, token: 'tok', idempotencyKey: 'k1' });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://api/x');
    expect(init?.method).toBe('POST');
    expect(init?.body).toBe('{"a":1}');
    expect(init?.headers).toMatchObject({
      Authorization: 'Bearer tok',
      'Idempotency-Key': 'k1',
      'Content-Type': 'application/json',
    });
  });

  it('sends no Authorization header when there is no token', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockReturnValue(reply(200, {}));
    await apiFetch('http://api', '/x', { token: undefined });
    expect(fetchMock.mock.calls[0]![1]?.headers).not.toHaveProperty('Authorization');
  });

  it('turns the backend problem format into an ApiError', async () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(
      reply(409, { title: 'Conflict', detail: 'Insufficient funds', code: 'INSUFFICIENT_FUNDS' }),
    );

    const error = await apiFetch('http://api', '/x', { token: 't' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: 'INSUFFICIENT_FUNDS', message: 'Insufficient funds' });
  });

  it('falls back to a generic error when the failure has no JSON body', async () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(Promise.resolve(new Response('boom', { status: 502 })));
    await expect(apiFetch('http://api', '/x', { token: 't' })).rejects.toMatchObject({ status: 502, code: 'HTTP_502' });
  });

  it('reports an unreachable server as NETWORK', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(apiFetch('http://api', '/x', { token: 't' })).rejects.toMatchObject({ status: 0, code: 'NETWORK' });
  });
});
