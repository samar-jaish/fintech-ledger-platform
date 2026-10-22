import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './client';
import { openNotificationStream, type NotificationItem } from './notifications';

const item: NotificationItem = {
  id: 5,
  transactionId: 't1',
  fromAccountId: 'a1',
  toAccountId: 'b1',
  amount: 10,
  message: 'Transfer of 10',
  createdAt: '2026-01-01T10:00:00Z',
  direction: 'RECEIVED',
};

/** A fake response whose body arrives in the given pieces. */
function streamOf(...pieces: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      pieces.forEach((p) => controller.enqueue(encoder.encode(p)));
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

afterEach(() => vi.unstubAllGlobals());

describe('openNotificationStream', () => {
  it('sends the token and the last id it saw, and delivers each notification', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      streamOf(': keep-alive\n\n', `id: 5\nevent: notification\ndata: ${JSON.stringify(item)}`, '\n\n'),
    );
    vi.stubGlobal('fetch', fetchMock);
    const received: NotificationItem[] = [];
    const onOpen = vi.fn();

    await openNotificationStream({
      token: 'tok',
      lastEventId: 4,
      signal: new AbortController().signal,
      onOpen,
      onNotification: (n) => received.push(n),
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:8084/api/v1/notifications/stream');
    expect(init.headers).toMatchObject({ Authorization: 'Bearer tok', 'Last-Event-ID': '4', Accept: 'text/event-stream' });
    expect(onOpen).toHaveBeenCalledOnce();
    expect(received).toEqual([item]);
  });

  it('does not send Last-Event-ID on the first connection', async () => {
    const fetchMock = vi.fn().mockResolvedValue(streamOf());
    vi.stubGlobal('fetch', fetchMock);
    await openNotificationStream({ token: 't', signal: new AbortController().signal, onNotification: vi.fn() });
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).not.toHaveProperty('Last-Event-ID');
  });

  it('skips a message that is not valid JSON and keeps reading', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      streamOf('event: notification\ndata: {oops\n\n', `event: notification\ndata: ${JSON.stringify(item)}\n\n`),
    ));
    const received: NotificationItem[] = [];
    await openNotificationStream({ token: 't', signal: new AbortController().signal, onNotification: (n) => received.push(n) });
    expect(received).toEqual([item]);
  });

  it('rejects when the server refuses the stream', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('no', { status: 401 })));
    await expect(
      openNotificationStream({ token: 't', signal: new AbortController().signal, onNotification: vi.fn() }),
    ).rejects.toMatchObject({ status: 401 });
  });

  it('rejects with a network error when nothing answers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(
      openNotificationStream({ token: 't', signal: new AbortController().signal, onNotification: vi.fn() }),
    ).rejects.toBeInstanceOf(ApiError);
  });
});
