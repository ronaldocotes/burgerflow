import { useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { CartLine, OrderType, QuoteRequest, QuoteResponse } from '@/types/cart';

const DEBOUNCE_MS = 400;

// Recota o carrinho no servidor com debounce 400ms e guard de sequencia.
// O total NUNCA e calculado no front — vem do POST /orders/quote.
export function useQuote(lines: CartLine[], orderType: OrderType) {
  const [quote, setQuote] = useState<QuoteResponse | null>(null);
  const [quoting, setQuoting] = useState(false);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const seqRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // Limpa debounce anterior
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }

    if (lines.length === 0) {
      setQuote(null);
      setQuoteError(null);
      setQuoting(false);
      seqRef.current += 1; // invalida qualquer chamada pendente
      return;
    }

    setQuoting(true);
    setQuoteError(null);
    const seq = ++seqRef.current;

    timerRef.current = setTimeout(() => {
      const body: QuoteRequest = {
        orderType,
        items: lines.map((l) => ({ ...l.item, quantity: l.quantity })),
      };

      api
        .post<QuoteResponse>('/orders/quote', body)
        .then((res) => {
          if (seq !== seqRef.current) return; // descarta resposta desatualizada
          setQuote(res);
        })
        .catch((err) => {
          if (seq !== seqRef.current) return;
          setQuote(null);
          setQuoteError(
            err instanceof Error ? err.message : 'Nao foi possivel calcular o total.',
          );
        })
        .finally(() => {
          if (seq === seqRef.current) setQuoting(false);
        });
    }, DEBOUNCE_MS);

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [lines, orderType]);

  return { quote, quoting, quoteError };
}
