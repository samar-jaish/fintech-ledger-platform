import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { transfer, type TransferBody } from '../api/ledger';
import { useIdempotencyKey } from '../api/idempotency';
import { isUuid, parseAmount } from '../format';
import { useAuth } from '../auth/AuthContext';
import { ErrorNote } from './ErrorNote';

export function TransferForm({ accountId }: { accountId: string }) {
  const { getToken } = useAuth();
  const queryClient = useQueryClient();
  const keys = useIdempotencyKey();
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [problem, setProblem] = useState<string>();
  const [done, setDone] = useState<string>();

  const send = useMutation({
    // The key is chosen from the content: retrying the same transfer reuses it, so it cannot be applied twice.
    mutationFn: (body: TransferBody) => transfer(getToken(), body, keys.keyFor(body)),
    onSuccess: async (result) => {
      keys.done();
      setDone(result.replayed ? 'This transfer was already applied. Nothing was sent twice.' : 'Transfer sent.');
      setTo('');
      setAmount('');
      setDescription('');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['account', accountId] }),
        queryClient.invalidateQueries({ queryKey: ['accounts'] }),
        queryClient.invalidateQueries({ queryKey: ['statement', accountId] }),
      ]);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setDone(undefined);
    const value = parseAmount(amount);
    if (!isUuid(to)) return setProblem('Enter the receiving account id (it looks like 3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90).');
    if (to.trim().toLowerCase() === accountId.toLowerCase()) return setProblem('Choose a different account to send to.');
    if (value === undefined) return setProblem('Enter a positive amount with at most 4 decimals.');
    setProblem(undefined);
    const body: TransferBody = { fromAccountId: accountId, toAccountId: to.trim().toLowerCase(), amount: value };
    if (description.trim()) body.description = description.trim();
    send.mutate(body);
  }

  return (
    <form onSubmit={submit} className="form" aria-label="Send money">
      <label>
        To account id
        <input value={to} onChange={(e) => setTo(e.target.value)} placeholder="Receiving account id" spellCheck={false} />
      </label>
      <label>
        Amount
        <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="25.00" />
      </label>
      <label>
        Note (optional)
        <input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={255} />
      </label>
      {problem && (
        <p className="error" role="alert">
          {problem}
        </p>
      )}
      <button className="btn" disabled={send.isPending}>
        {send.isPending ? 'Sending…' : 'Send money'}
      </button>
      {done && <p className="ok" role="status">{done}</p>}
      <ErrorNote error={send.error} />
    </form>
  );
}
