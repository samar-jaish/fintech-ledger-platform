import { config } from '../config';
import { ApiError, apiFetch } from './client';
import { createSseParser } from './sse';

/** SENT and RECEIVED are from the signed-in user's point of view; SELF means both accounts are theirs. */
export type Direction = 'SENT' | 'RECEIVED' | 'SELF';

export interface NotificationItem {
  id: number;
  transactionId: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  message: string;
  createdAt: string;
  direction: Direction;
}

/** The newest notifications for the signed-in user, newest first. */
export const listNotifications = (token: string | undefined, limit = 20) =>
  apiFetch<NotificationItem[]>(config.notificationApi, `/api/v1/notifications?limit=${limit}`, { token });

interface StreamOptions {
  token: string | undefined;
  /** The id of the last notification already seen: the server first sends whatever came after it. */
  lastEventId?: number;
  signal: AbortSignal;
  onOpen?: () => void;
  onNotification: (item: NotificationItem) => void;
}

/**
 * Opens the live stream and calls onNotification for each one. Resolves when the server ends the stream (it does so
 * every few minutes, so the token is checked again on reconnect); rejects if the connection fails or is refused.
 */
export async function openNotificationStream(options: StreamOptions): Promise<void> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.lastEventId !== undefined) headers['Last-Event-ID'] = String(options.lastEventId);

  let response: Response;
  try {
    response = await fetch(`${config.notificationApi}/api/v1/notifications/stream`, { headers, signal: options.signal });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Could not reach the notification service.');
  }
  if (!response.ok || !response.body) {
    throw new ApiError(response.status, `HTTP_${response.status}`, `The notification stream was refused (${response.status}).`);
  }
  options.onOpen?.();

  const parse = createSseParser((message) => {
    if (message.event !== 'notification') return;
    try {
      options.onNotification(JSON.parse(message.data) as NotificationItem);
    } catch {
      // a message that is not valid JSON is skipped; it must not break the stream
    }
  });
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      parse(decoder.decode(value, { stream: true }));
    }
  } catch (error) {
    if (options.signal.aborted) return; // we closed it ourselves (sign-out or leaving the page)
    throw new ApiError(0, 'NETWORK', error instanceof Error ? error.message : 'The notification stream was interrupted.');
  }
}
