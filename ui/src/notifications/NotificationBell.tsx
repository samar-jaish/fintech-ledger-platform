import { useState } from 'react';
import { formatAmount, formatDateTime, shortId } from '../format';
import type { NotificationItem } from '../api/notifications';
import { useNotificationFeed } from './useNotificationFeed';

function describe(n: NotificationItem): string {
  const amount = formatAmount(n.amount);
  switch (n.direction) {
    case 'SENT':
      return `You sent ${amount} to account ${shortId(n.toAccountId)}`;
    case 'RECEIVED':
      return `You received ${amount} from account ${shortId(n.fromAccountId)}`;
    default:
      return `You moved ${amount} between your accounts`;
  }
}

/** A bell in the header: a badge counts what is new, and the panel lists recent activity as it happens. */
export function NotificationBell() {
  const { items, unread, live, markRead } = useNotificationFeed();
  const [open, setOpen] = useState(false);

  function toggle() {
    setOpen((was) => !was);
    markRead();
  }

  return (
    <div className="bell" onKeyDown={(e) => e.key === 'Escape' && setOpen(false)}>
      <button
        className="btn btn-quiet bell-button"
        onClick={toggle}
        aria-expanded={open}
        aria-label={unread > 0 ? `Notifications, ${unread} new` : 'Notifications'}
      >
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {unread > 0 && <span className="bell-badge" aria-hidden="true">{unread > 9 ? '9+' : unread}</span>}
      </button>
      {open && (
        <section className="bell-panel" aria-label="Recent activity">
          <p className="hint">{live ? 'Live: new activity appears here as it happens.' : 'Reconnecting…'}</p>
          {items.length === 0 ? (
            <p className="muted">No activity yet. Send or receive money and it shows up here.</p>
          ) : (
            <ul>
              {items.map((n) => (
                <li key={n.id}>
                  <span>{describe(n)}</span>
                  <span className="muted">{formatDateTime(n.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}
