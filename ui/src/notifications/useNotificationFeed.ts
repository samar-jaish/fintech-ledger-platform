import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthContext';
import { listNotifications, openNotificationStream, type NotificationItem } from '../api/notifications';

const KEEP = 30;
const FAST_RECONNECT_MS = 250; // the server ends a stream on purpose every few minutes: come straight back
const MAX_BACKOFF_MS = 30_000;

/** Waits, but wakes up at once if the feed is being closed. */
function sleep(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}

/**
 * The signed-in user's notifications: the recent ones, then new ones as they happen. It reconnects by itself (asking
 * for what it missed), and refreshes the money screens when something arrives, so balances change without a reload.
 */
export function useNotificationFeed() {
  const { getToken } = useAuth();
  const queryClient = useQueryClient();
  // The token changes whenever it is renewed. The feed keeps running through that and reads the newest token each time
  // it (re)connects, rather than restarting.
  const tokenRef = useRef(getToken);
  tokenRef.current = getToken;
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unread, setUnread] = useState(0);
  const [live, setLive] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    const seen = new Set<number>();
    let lastId: number | undefined;

    function remember(item: NotificationItem) {
      seen.add(item.id);
      lastId = Math.max(lastId ?? 0, item.id);
    }

    function arrived(item: NotificationItem) {
      if (seen.has(item.id)) return; // around a reconnect the same one can come twice
      remember(item);
      setItems((previous) => [item, ...previous].slice(0, KEEP));
      setUnread((count) => count + 1);
      // Money moved: the numbers on screen are out of date.
      for (const key of ['accounts', 'account', 'statement', 'portfolio', 'trades']) {
        void queryClient.invalidateQueries({ queryKey: [key] });
      }
    }

    async function run() {
      try {
        const history = await listNotifications(tokenRef.current());
        if (signal.aborted) return;
        history.forEach(remember);
        setItems(history.slice(0, KEEP));
      } catch {
        // the list is a convenience: the live stream below still works, and reconnecting will fill the gap
      }

      let backoff = 1000;
      while (!signal.aborted) {
        let wait = FAST_RECONNECT_MS;
        try {
          await openNotificationStream({
            token: tokenRef.current(),
            lastEventId: lastId,
            signal,
            onOpen: () => { setLive(true); backoff = 1000; },
            onNotification: arrived,
          });
        } catch {
          wait = backoff;
          backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
        if (signal.aborted) return;
        setLive(false);
        await sleep(wait, signal);
      }
    }

    void run();
    return () => controller.abort();
  }, [queryClient]);

  return { items, unread, live, markRead: () => setUnread(0) };
}
