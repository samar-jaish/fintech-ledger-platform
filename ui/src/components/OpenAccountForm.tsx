import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createAccount, type Account } from '../api/ledger';
import { useAuth } from '../auth/AuthContext';
import { ErrorNote } from './ErrorNote';

export function OpenAccountForm({ onOpened }: { onOpened?: (account: Account) => void }) {
  const { state, getToken } = useAuth();
  const queryClient = useQueryClient();
  const [ownerName, setOwnerName] = useState(state.status === 'authenticated' ? state.displayName : '');
  const [currency, setCurrency] = useState('USD');

  const open = useMutation({
    mutationFn: () => createAccount(getToken(), ownerName.trim(), currency.trim().toUpperCase()),
    onSuccess: async (account) => {
      await queryClient.invalidateQueries({ queryKey: ['accounts'] });
      onOpened?.(account);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    open.mutate();
  }

  return (
    <form onSubmit={submit} className="form" aria-label="Open an account">
      <label>
        Account holder
        <input value={ownerName} onChange={(e) => setOwnerName(e.target.value)} maxLength={100} required />
      </label>
      <label>
        Currency
        <input value={currency} onChange={(e) => setCurrency(e.target.value)} maxLength={3} pattern="[A-Za-z]{3}" required />
      </label>
      <button className="btn" disabled={open.isPending}>
        {open.isPending ? 'Opening…' : 'Open account'}
      </button>
      <ErrorNote error={open.error} />
    </form>
  );
}
