import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { NotificationItem } from '../api/notifications';
import { NotificationBell } from './NotificationBell';

vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ getToken }) }));
const getToken = () => 'token-1';

const api = vi.hoisted(() => ({
  listNotifications: vi.fn(),
  openNotificationStream: vi.fn(),
}));
vi.mock('../api/notifications', () => api);

const A = '11111111-aaaa-4aaa-8aaa-111111111111';
const B = '22222222-bbbb-4bbb-8bbb-222222222222';
const note = (id: number, direction: NotificationItem['direction'], amount = 10): NotificationItem => ({
  id, transactionId: `t${id}`, fromAccountId: A, toAccountId: B, amount, message: 'm', createdAt: '2026-01-01T10:00:00Z', direction,
});

/** The stream mock: remembers its callbacks so a test can push notifications, and stays open until closed. */
let push: (n: NotificationItem) => void;
let lastEventIdSeen: (number | undefined)[];

beforeEach(() => {
  vi.clearAllMocks();
  lastEventIdSeen = [];
  api.listNotifications.mockResolvedValue([note(1, 'SENT')]);
  api.openNotificationStream.mockImplementation(
    (o: { lastEventId?: number; signal: AbortSignal; onOpen: () => void; onNotification: (n: NotificationItem) => void }) => {
      lastEventIdSeen.push(o.lastEventId);
      push = o.onNotification;
      o.onOpen();
      return new Promise<void>((resolve) => o.signal.addEventListener('abort', () => resolve()));
    },
  );
});

function renderBell() {
  const client = new QueryClient();
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  render(
    <QueryClientProvider client={client}>
      <NotificationBell />
    </QueryClientProvider>,
  );
  return { invalidate };
}

describe('notification bell', () => {
  it('shows recent activity from the list, with no unread badge', async () => {
    renderBell();
    await waitFor(() => expect(api.listNotifications).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));
    expect(await screen.findByText(`You sent 10.00 to account ${B.slice(0, 8)}`)).toBeInTheDocument();
    expect(screen.getByText(/Live/)).toBeInTheDocument();
  });

  it('counts a live notification, shows it, and refreshes the money screens', async () => {
    const { invalidate } = renderBell();
    await waitFor(() => expect(api.openNotificationStream).toHaveBeenCalled());

    act(() => push(note(2, 'RECEIVED', 25)));

    const bell = await screen.findByRole('button', { name: 'Notifications, 1 new' });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['account'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['statement'] });

    await userEvent.click(bell);
    expect(screen.getByText(`You received 25.00 from account ${A.slice(0, 8)}`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument(); // opening clears the badge
  });

  it('ignores a notification it already has (the same one can arrive twice around a reconnect)', async () => {
    renderBell();
    await waitFor(() => expect(api.openNotificationStream).toHaveBeenCalled());

    act(() => push(note(2, 'RECEIVED')));
    act(() => push(note(2, 'RECEIVED')));

    expect(await screen.findByRole('button', { name: 'Notifications, 1 new' })).toBeInTheDocument();
  });

  it('opens the stream asking only for what came after the newest notification in the list', async () => {
    api.listNotifications.mockResolvedValue([note(9, 'SENT'), note(4, 'RECEIVED')]);
    renderBell();
    await waitFor(() => expect(api.openNotificationStream).toHaveBeenCalled());
    expect(lastEventIdSeen).toEqual([9]);
  });

  it('says so when there is nothing yet, and when the stream is not connected', async () => {
    api.listNotifications.mockResolvedValue([]);
    api.openNotificationStream.mockRejectedValue(new Error('down'));
    renderBell();
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));
    expect(await screen.findByText(/No activity yet/)).toBeInTheDocument();
    expect(screen.getByText('Reconnecting…')).toBeInTheDocument();
  });

  it('closes the panel on Escape', async () => {
    renderBell();
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));
    expect(screen.getByRole('region', { name: 'Recent activity' })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('region', { name: 'Recent activity' })).not.toBeInTheDocument();
  });
});
