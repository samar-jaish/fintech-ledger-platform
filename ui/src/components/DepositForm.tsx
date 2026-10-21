import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { deposit } from '../api/ledger';
import { useIdempotencyKey } from '../api/idempotency';
import { parseAmount } from '../format';
import { useAuth } from '../auth/AuthContext';
import { ErrorNote } from './ErrorNote';

export function DepositForm({ accountId }: { accountId: string }) {
  const { getToken } = useAuth();
  const queryClient = useQueryClient();
  const keys = useIdempotencyKey();
  const [amount, setAmount] = useState('');
  const [invalid, setInvalid] = useState(false);

  const add = useMutation({
    mutationFn: (value: number) => deposit(getToken(), accountId, value, keys.keyFor({ accountId, value })),
    onSuccess: async () => {
      keys.done();
      setAmount('');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['account', accountId] }),
        queryClient.invalidateQueries({ queryKey: ['accounts'] }),
        queryClient.invalidateQueries({ queryKey: ['statement', accountId] }),
      ]);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const value = parseAmount(amount);
    setInvalid(value === undefined);
    if (value !== undefined) add.mutate(value);
  }

  return (
    <form onSubmit={submit} className="form" aria-label="Add money">
      <label>
        Amount to add
        <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="100.00" />
      </label>
      {invalid && (
        <p className="error" role="alert">
          Enter a positive amount with at most 4 decimals.
        </p>
      )}
      <button className="btn" disabled={add.isPending}>
        {add.isPending ? 'Adding…' : 'Add money'}
      </button>
      <p className="hint">Simulated cash-in: there are no real payment rails.</p>
      <ErrorNote error={add.error} />
    </form>
  );
}
