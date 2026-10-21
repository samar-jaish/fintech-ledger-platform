import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { placeTrade, type Side, type Trade, type TradeBody } from '../api/trading';
import { useIdempotencyKey } from '../api/idempotency';
import { formatMoney, formatQuantity, parseQuantity } from '../format';
import { useAuth } from '../auth/AuthContext';
import { ErrorNote } from './ErrorNote';

// Symbols the simulated market knows. Typing any other valid symbol is allowed; the server decides.
const SUGGESTIONS = ['AAPL', 'MSFT', 'GOOGL', 'AMZN', 'TSLA', 'NVDA', 'META', 'JPM', 'V', 'KO', 'DIS', 'NFLX'];

function summary(trade: Trade): string {
  if (trade.status === 'PENDING') {
    return 'Accepted. The ledger has not confirmed the cash yet; it is retried automatically and the portfolio will update.';
  }
  const verb = trade.side === 'BUY' ? 'Bought' : 'Sold';
  return `${verb} ${formatQuantity(trade.quantity)} ${trade.symbol} at ${formatMoney(trade.price, trade.currency)} (total ${formatMoney(trade.notional, trade.currency)}).`;
}

export function TradeForm({ accountId }: { accountId: string }) {
  const { getToken } = useAuth();
  const queryClient = useQueryClient();
  const keys = useIdempotencyKey();
  const [symbol, setSymbol] = useState('AAPL');
  const [side, setSide] = useState<Side>('BUY');
  const [quantity, setQuantity] = useState('');
  const [problem, setProblem] = useState<string>();
  const [outcome, setOutcome] = useState<string>();

  const trade = useMutation({
    mutationFn: (body: TradeBody) => placeTrade(getToken(), body, keys.keyFor(body)),
    onSuccess: async (result) => {
      keys.done();
      setOutcome(summary(result));
      setQuantity('');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['portfolio', accountId] }),
        queryClient.invalidateQueries({ queryKey: ['trades', accountId] }),
        queryClient.invalidateQueries({ queryKey: ['account', accountId] }),
        queryClient.invalidateQueries({ queryKey: ['accounts'] }),
        queryClient.invalidateQueries({ queryKey: ['statement', accountId] }),
      ]);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setOutcome(undefined);
    const qty = parseQuantity(quantity);
    if (!/^[A-Za-z][A-Za-z0-9.-]{0,9}$/.test(symbol.trim())) return setProblem('Enter a stock symbol such as AAPL.');
    if (qty === undefined) return setProblem('Enter a positive quantity with at most 6 decimals.');
    setProblem(undefined);
    trade.mutate({ accountId, symbol: symbol.trim().toUpperCase(), side, quantity: qty });
  }

  return (
    <form onSubmit={submit} className="form" aria-label="Place a trade">
      <div className="row">
        <label>
          Symbol
          <input value={symbol} onChange={(e) => setSymbol(e.target.value)} list="symbols" maxLength={10} />
          <datalist id="symbols">
            {SUGGESTIONS.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
        </label>
        <label>
          Side
          <select value={side} onChange={(e) => setSide(e.target.value as Side)}>
            <option value="BUY">Buy</option>
            <option value="SELL">Sell</option>
          </select>
        </label>
        <label>
          Quantity
          <input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} placeholder="5" />
        </label>
      </div>
      {problem && (
        <p className="error" role="alert">
          {problem}
        </p>
      )}
      <button className="btn" disabled={trade.isPending}>
        {trade.isPending ? 'Placing…' : side === 'BUY' ? 'Buy at market price' : 'Sell at market price'}
      </button>
      {outcome && <p className="ok" role="status">{outcome}</p>}
      <ErrorNote error={trade.error} />
      <p className="hint">Prices come from a simulated market. The price is fixed by the server when the trade runs.</p>
    </form>
  );
}
